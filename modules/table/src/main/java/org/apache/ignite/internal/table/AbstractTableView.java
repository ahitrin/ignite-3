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

package org.apache.ignite.internal.table;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.ignite.internal.schema.SchemaRegistry;
import org.apache.ignite.lang.IgniteException;
import org.apache.ignite.lang.IgniteInternalException;

/**
 * Base class for Table views.
 */
abstract class AbstractTableView {
    /** Internal table. */
    protected final InternalTable tbl;

    /** Schema registry. */
    protected final SchemaRegistry schemaReg;

    /**
     * Constructor.
     *
     * @param tbl       Internal table.
     * @param schemaReg Schema registry.
     */
    protected AbstractTableView(InternalTable tbl, SchemaRegistry schemaReg) {
        this.tbl = tbl;
        this.schemaReg = schemaReg;
    }

    /**
     * Waits for operation completion.
     *
     * @param fut Future to wait to.
     * @param <T> Future result type.
     * @return Future result.
     */
    protected <T> T sync(CompletableFuture<T> fut) {
        try {
            return fut.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt flag.

            throw convertException(e);
        } catch (ExecutionException e) {
            throw convertException(e.getCause());
        } catch (IgniteInternalException e) {
            throw convertException(e);
        }
    }

    /**
     * Converts an internal exception to a public one.
     *
     * @param th Internal exception.
     * @return Public exception.
     */
    protected IgniteException convertException(Throwable th) {
        if (th instanceof IgniteException) {
            return (IgniteException) th;
        }

        //TODO: IGNITE-20181 KV/Binary view public API should only throw public exceptions for the end user.
        return new IgniteException(th);
    }
}
