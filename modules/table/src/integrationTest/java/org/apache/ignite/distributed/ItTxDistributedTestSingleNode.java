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

package org.apache.ignite.distributed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.ignite.internal.configuration.testframework.ConfigurationExtension;
import org.apache.ignite.internal.configuration.testframework.InjectConfiguration;
import org.apache.ignite.internal.raft.Loza;
import org.apache.ignite.internal.raft.Peer;
import org.apache.ignite.internal.raft.RaftNodeId;
import org.apache.ignite.internal.raft.configuration.RaftConfiguration;
import org.apache.ignite.internal.raft.server.impl.JraftServerImpl;
import org.apache.ignite.internal.replicator.TablePartitionId;
import org.apache.ignite.internal.schema.configuration.GcConfiguration;
import org.apache.ignite.internal.schema.configuration.TablesConfiguration;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.table.TableImpl;
import org.apache.ignite.internal.table.TxAbstractTest;
import org.apache.ignite.internal.table.distributed.raft.PartitionListener;
import org.apache.ignite.internal.tx.InternalTransaction;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.network.ClusterService;
import org.apache.ignite.network.NodeFinder;
import org.apache.ignite.table.Table;
import org.apache.ignite.tx.Transaction;
import org.apache.ignite.tx.TransactionOptions;
import org.apache.ignite.utils.ClusterServiceTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Distributed transaction test using a single partition table.
 */
@ExtendWith(ConfigurationExtension.class)
public class ItTxDistributedTestSingleNode extends TxAbstractTest {
    protected static final int ACC_TABLE_ID = 1;

    protected static final int CUST_TABLE_ID = 2;

    protected static final String ACC_TABLE_NAME = "accounts";

    protected static final String CUST_TABLE_NAME = "customers";

    //TODO fsync can be turned on again after https://issues.apache.org/jira/browse/IGNITE-20195
    @InjectConfiguration("mock: { fsync: false }")
    private static RaftConfiguration raftConfiguration;

    @InjectConfiguration
    private static GcConfiguration gcConfig;

    @InjectConfiguration("mock.tables.foo {}")
    private static TablesConfiguration tablesConfig;

    /**
     * Returns a count of nodes.
     *
     * @return Nodes.
     */
    protected int nodes() {
        return 1;
    }

    /**
     * Returns a count of replicas.
     *
     * @return Replicas.
     */
    protected int replicas() {
        return 1;
    }

    /**
     * Returns {@code true} to disable collocation by using dedicated client node.
     *
     * @return {@code true} to disable collocation.
     */
    protected boolean startClient() {
        return true;
    }

    private final TestInfo testInfo;

    protected ItTxTestCluster txTestCluster;

    /**
     * The constructor.
     *
     * @param testInfo Test info.
     */
    public ItTxDistributedTestSingleNode(TestInfo testInfo) {
        this.testInfo = testInfo;
    }

    /**
     * Initialize the test state.
     */
    @Override
    @BeforeEach
    public void before() throws Exception {
        txTestCluster = new ItTxTestCluster(
                testInfo,
                raftConfiguration,
                gcConfig,
                tablesConfig,
                workDir,
                nodes(),
                replicas(),
                startClient(),
                timestampTracker
        );
        txTestCluster.prepareCluster();

        this.igniteTransactions = txTestCluster.igniteTransactions;

        accounts = txTestCluster.startTable(ACC_TABLE_NAME, ACC_TABLE_ID, ACCOUNTS_SCHEMA);
        customers = txTestCluster.startTable(CUST_TABLE_NAME, CUST_TABLE_ID, CUSTOMERS_SCHEMA);

        log.info("Tables have been started");
    }

    /**
     * Shutdowns all cluster nodes after each test.
     *
     * @throws Exception If failed.
     */
    @AfterEach
    public void after() throws Exception {
        txTestCluster.shutdownCluster();
    }

    /**
     * Starts a node.
     *
     * @param name Node name.
     * @param port Local port.
     * @param nodeFinder Node finder.
     * @return The client cluster view.
     */
    protected static ClusterService startNode(TestInfo testInfo, String name, int port,
            NodeFinder nodeFinder) {
        var network = ClusterServiceTestUtils.clusterService(testInfo, port, nodeFinder);

        network.start();

        return network;
    }

    /** {@inheritDoc} */
    @Override
    protected TxManager clientTxManager() {
        return txTestCluster.clientTxManager;
    }

    /** {@inheritDoc} */
    @Override
    protected TxManager txManager(Table t) {
        var clients = txTestCluster.raftClients.get(t.name());

        Peer leader = clients.get(0).leader();

        assertNotNull(leader);

        TxManager manager = txTestCluster.txManagers.get(leader.consistentId());

        assertNotNull(manager);

        return manager;
    }

    /**
     * Check the storage of partition is the same across all nodes.
     * The checking is based on {@link MvPartitionStorage#lastAppliedIndex()} that is increased on all update storage operation.
     * TODO: IGNITE-18869 The method must be updated when a proper way to compare storages will be implemented.
     *
     * @param table The table.
     * @param partId Partition id.
     * @return True if {@link MvPartitionStorage#lastAppliedIndex()} is equivalent across all nodes, false otherwise.
     */
    @Override
    protected boolean assertPartitionsSame(TableImpl table, int partId) {
        long storageIdx = 0;

        for (Map.Entry<String, Loza> entry : txTestCluster.raftServers.entrySet()) {
            Loza svc = entry.getValue();

            var server = (JraftServerImpl) svc.server();

            var groupId = new TablePartitionId(table.tableId(), partId);

            Peer serverPeer = server.localPeers(groupId).get(0);

            org.apache.ignite.raft.jraft.RaftGroupService grp = server.raftGroupService(new RaftNodeId(groupId, serverPeer));

            var fsm = (JraftServerImpl.DelegatingStateMachine) grp.getRaftNode().getOptions().getFsm();

            PartitionListener listener = (PartitionListener) fsm.getListener();

            MvPartitionStorage storage = listener.getMvStorage();

            if (storageIdx == 0) {
                storageIdx = storage.lastAppliedIndex();
            } else if (storageIdx != storage.lastAppliedIndex()) {
                return false;
            }
        }

        return true;
    }

    @Test
    public void testIgniteTransactionsAndReadTimestamp() {
        Transaction readWriteTx = igniteTransactions.begin();
        assertFalse(readWriteTx.isReadOnly());
        assertNull(((InternalTransaction) readWriteTx).readTimestamp());

        Transaction readOnlyTx = igniteTransactions.begin(new TransactionOptions().readOnly(true));
        assertTrue(readOnlyTx.isReadOnly());
        assertNotNull(((InternalTransaction) readOnlyTx).readTimestamp());

        readWriteTx.commit();

        Transaction readOnlyTx2 = igniteTransactions.begin(new TransactionOptions().readOnly(true));
        readOnlyTx2.rollback();
    }
}
