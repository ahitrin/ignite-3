/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.schemasync;

import static org.apache.ignite.internal.SessionUtils.executeUpdate;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.waitForCondition;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willSucceedIn;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.ignite.internal.ClusterPerTestIntegrationTest;
import org.apache.ignite.internal.ReplicationGroupsUtils;
import org.apache.ignite.internal.app.IgniteImpl;
import org.apache.ignite.internal.metastorage.server.raft.MetastorageGroupId;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.table.TableImpl;
import org.apache.ignite.internal.table.distributed.schema.CheckCatalogVersionOnAppendEntries;
import org.apache.ignite.internal.test.WatchListenerInhibitor;
import org.apache.ignite.internal.testframework.log4j2.LogInspector;
import org.apache.ignite.table.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests about interaction between Schema Synchronization and Replication.
 */
@SuppressWarnings("resource")
class ItSchemaSyncAndReplicationTest extends ClusterPerTestIntegrationTest {
    private static final int NODES_TO_START = 3;

    private static final String TABLE_NAME = "test";

    private final LogInspector appendEntriesInterceptorInspector = LogInspector.create(CheckCatalogVersionOnAppendEntries.class, true);

    @Override
    protected int initialNodes() {
        return NODES_TO_START;
    }

    @AfterEach
    void stopLogInspector() {
        appendEntriesInterceptorInspector.stop();
    }

    /**
     * The replication mechanism must not replicate commands for which schemas are not yet available on the node
     * to which replication happens (in Raft, it means that followers/learners cannot receive commands that they
     * cannot execute without waiting for schemas). This method tests this scenario.
     */
    @Test
    void laggingSchemasPreventPartitionDataReplication() throws Exception {
        createTestTableWith3Replicas();

        final int notInhibitedNodeIndex = 0;
        transferLeadershipsTo(notInhibitedNodeIndex);

        IgniteImpl nodeToInhibitMetaStorage = cluster.node(1);

        WatchListenerInhibitor listenerInhibitor = WatchListenerInhibitor.metastorageEventsInhibitor(nodeToInhibitMetaStorage);
        listenerInhibitor.startInhibit();

        try {
            CompletableFuture<?> rejectionTriggered = rejectionDueToMetadataLagTriggered();

            updateTableSchemaAt(notInhibitedNodeIndex);
            putToTableAt(notInhibitedNodeIndex);

            assertThat("Did not see rejections due to lagging metadata", rejectionTriggered, willSucceedIn(10, TimeUnit.SECONDS));

            assertTrue(solePartitionIsEmpty(nodeToInhibitMetaStorage), "Something was written to the partition");

            listenerInhibitor.stopInhibit();

            assertTrue(
                    waitForCondition(() -> !solePartitionIsEmpty(nodeToInhibitMetaStorage), 10_000),
                    "Nothing was written to partition even after inhibiting was cancelled"
            );
        } finally {
            listenerInhibitor.stopInhibit();
        }
    }

    private void createTestTableWith3Replicas() throws InterruptedException {
        String zoneSql = "create zone test_zone with partitions=1, replicas=3";
        String sql = "create table " + TABLE_NAME + " (key int primary key, val varchar(20))"
                + " with primary_zone='TEST_ZONE'";

        cluster.doInSession(0, session -> {
            executeUpdate(zoneSql, session);
            executeUpdate(sql, session);
        });

        waitForTableToStart();
    }

    private void waitForTableToStart() throws InterruptedException {
        // TODO: IGNITE-18733 - remove this wait because when a table creation query is executed, the table must be fully ready.

        BooleanSupplier tableStarted = () -> {
            int numberOfStartedRaftNodes = cluster.runningNodes()
                    .map(ReplicationGroupsUtils::tablePartitionIds)
                    .mapToInt(List::size)
                    .sum();
            return numberOfStartedRaftNodes == NODES_TO_START;
        };

        assertTrue(waitForCondition(tableStarted, 10_000), "Did not see all table RAFT nodes started");
    }

    private void transferLeadershipsTo(int nodeIndex) throws InterruptedException {
        cluster.transferLeadershipTo(nodeIndex, MetastorageGroupId.INSTANCE);
        cluster.transferLeadershipTo(nodeIndex, cluster.solePartitionId());
    }

    private CompletableFuture<?> rejectionDueToMetadataLagTriggered() {
        CompletableFuture<?> rejectionTriggered = new CompletableFuture<>();

        appendEntriesInterceptorInspector.addHandler(
                event -> event.getMessage().getFormattedMessage().startsWith("Metadata not yet available"),
                () -> rejectionTriggered.complete(null)
        );

        return rejectionTriggered;
    }

    private void putToTableAt(int nodeIndex) {
        cluster.node(nodeIndex)
                .tables()
                .table(TABLE_NAME)
                .keyValueView()
                .put(null, Tuple.create().set("key", 1), Tuple.create().set("val", "one"));
    }

    private void updateTableSchemaAt(int nodeIndex) {
        cluster.doInSession(nodeIndex, session -> {
            session.execute(null, "alter table " + TABLE_NAME + " add column added int");
        });
    }

    private static boolean solePartitionIsEmpty(IgniteImpl node) {
        MvPartitionStorage mvPartitionStorage = solePartitionStorage(node);
        RowId rowId = mvPartitionStorage.closestRowId(RowId.lowestRowId(0));
        return rowId == null;
    }

    private static MvPartitionStorage solePartitionStorage(IgniteImpl node) {
        TableImpl table = (TableImpl) node.tables().table(TABLE_NAME);

        MvPartitionStorage mvPartitionStorage = table.internalTable().storage().getMvPartition(0);

        assertThat(mvPartitionStorage, is(notNullValue()));

        return mvPartitionStorage;
    }
}
