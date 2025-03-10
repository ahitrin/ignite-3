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

package org.apache.ignite.internal.table.distributed.replication.request;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.ignite.internal.replicator.message.ReplicaRequest;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.table.distributed.replicator.action.RequestType;
import org.apache.ignite.network.annotations.Marshallable;

/**
 * Multiple row replica request.
 */
public interface MultipleRowReplicaRequest extends ReplicaRequest {
    Collection<BinaryRowMessage> binaryRowMessages();

    /**
     * Deserializes binary row byte buffers into binary rows.
     */
    default List<BinaryRow> binaryRows() {
        Collection<BinaryRowMessage> binaryRowMessages = binaryRowMessages();

        var result = new ArrayList<BinaryRow>(binaryRowMessages.size());

        for (BinaryRowMessage message : binaryRowMessages) {
            result.add(message.asBinaryRow());
        }

        return result;
    }

    @Marshallable
    RequestType requestType();
}
