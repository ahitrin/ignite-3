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

package org.apache.ignite.internal.sql.engine.util;

import org.apache.calcite.runtime.Resources;
import org.apache.calcite.sql.validate.SqlValidatorException;

/**
 * IgniteResource interface.
 */
public interface IgniteResource {
    IgniteResource INSTANCE = Resources.create(IgniteResource.class);

    @Resources.BaseMessage("Illegal alias. {0} is reserved name")
    Resources.ExInst<SqlValidatorException> illegalAlias(String a0);

    @Resources.BaseMessage("Cannot update field \"{0}\". You cannot update key, key fields or val field in case the val is a complex type")
    Resources.ExInst<SqlValidatorException> cannotUpdateField(String field);

    @Resources.BaseMessage("Illegal aggregate function. {0} is unsupported at the moment")
    Resources.ExInst<SqlValidatorException> unsupportedAggregationFunction(String a0);

    @Resources.BaseMessage("Illegal value of {0}. The value must be positive and less than Integer.MAX_VALUE (" + Integer.MAX_VALUE + ")")
    Resources.ExInst<SqlValidatorException> correctIntegerLimit(String a0);

    @Resources.BaseMessage("Invalid decimal literal")
    Resources.ExInst<SqlValidatorException> decimalLiteralInvalid();

    @Resources.BaseMessage
            ("Values passed to {0} operator must have compatible types. Dynamic parameter requires adding explicit type cast")
    Resources.ExInst<SqlValidatorException> operationRequiresExplicitCast(String operation);

    @Resources.BaseMessage("Assignment from {0} to {1} can not be performed. Dynamic parameter requires adding explicit type cast")
    Resources.ExInst<SqlValidatorException> assignmentRequiresExplicitCast(String type1, String type2);
}
