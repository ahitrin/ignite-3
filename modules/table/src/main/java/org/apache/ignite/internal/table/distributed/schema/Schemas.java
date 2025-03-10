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

package org.apache.ignite.internal.table.distributed.schema;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.hlc.HybridTimestamp;

/**
 * Provides access to table schemas.
 */
public interface Schemas {
    /**
     * Obtains a future that completes when all schemas activating not later than the given timestamp are available.
     *
     * @param ts Timestamp we are interested in. This is the timestamp transaction processing logic is interested in (like beginTs or
     *     commitTs), not the timestamp after subtraction described in section 'Waiting for safe time in the past' of
     *     <a href="https://cwiki.apache.org/confluence/display/IGNITE/IEP-98:+Schema+Synchronization">IEP-98</a>
     * @return Future that completes when all schemas activating not later than the given timestamp are available.
     */
    CompletableFuture<?> waitForSchemasAvailability(HybridTimestamp ts);

    /**
     * Obtains a future that completes when the given schema version becomes available.
     *
     * @param tableId ID of the table of interest.
     * @param schemaVersion ID of the schema version.
     * @return Future that completes when the given schema version becomes available.
     */
    CompletableFuture<?> waitForSchemaAvailability(int tableId, int schemaVersion);

    /**
     * Returns all schema versions between (including) the two that were effective at the given timestamps.
     *
     * @param tableId ID of the table which schemas need to be considered.
     * @param fromIncluding Start timestamp.
     * @param toIncluding End timestamp.
     * @return All schema versions between (including) the two that were effective at the given timestamps.
     */
    List<FullTableSchema> tableSchemaVersionsBetween(int tableId, HybridTimestamp fromIncluding, HybridTimestamp toIncluding);

    /**
     * Returns all schema versions between (including) the one that was effective at the given timestamp and
     * the one identified by a schema version ID. If the starting schema (the one effective at fromIncluding)
     * is actually a later schema than the one identified by toIncluding, then an empty list is returned.
     *
     * @param tableId ID of the table which schemas need to be considered.
     * @param fromIncluding Start timestamp.
     * @param toIncluding End schema version ID.
     * @return All schema versions between (including) the given timestamp and schema version.
     */
    List<FullTableSchema> tableSchemaVersionsBetween(int tableId, HybridTimestamp fromIncluding, int toIncluding);
}
