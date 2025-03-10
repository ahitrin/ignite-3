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

package org.apache.ignite.internal.tx;

import static java.util.Objects.requireNonNull;

/**
 * Transaction state.
 */
public enum TxState {
    PENDING,
    FINISHING,
    ABORTED,
    COMMITED;

    private static final boolean[][] TRANSITION_MATRIX = {
            { false, true,  false, true,  true },
            { false, true,  true,  true,  true },
            { false, false, false, true,  true },
            { false, false, false, true,  false},
            { false, false, false, false, true },
    };

    /**
     * Checks the correctness of the transition between transaction states.
     *
     * @param before State before.
     * @param after State after.
     * @return Whether the transition is correct.
     */
    public static boolean checkTransitionCorrectness(TxState before, TxState after) {
        requireNonNull(after);

        int beforeOrd = before == null ? 0 : before.ordinal() + 1;
        int afterOrd = after.ordinal() + 1;

        return TRANSITION_MATRIX[beforeOrd][afterOrd];
    }
}
