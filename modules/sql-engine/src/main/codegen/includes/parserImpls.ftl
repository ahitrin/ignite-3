<#--
// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements.  See the NOTICE file distributed with
// this work for additional information regarding copyright ownership.
// The ASF licenses this file to you under the Apache License, Version 2.0
// (the "License"); you may not use this file except in compliance with
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
-->

boolean IfNotExistsOpt() :
{
}
{
    <IF> <NOT> <EXISTS> { return true; }
|
    { return false; }
}

SqlNodeList CreateTableOptionList() :
{
    List<SqlNode> list = new ArrayList<SqlNode>();
    final Span s = Span.of();
}
{
    CreateTableOption(list)
    (
        <COMMA> { s.add(this); } CreateTableOption(list)
    )*
    {
        return new SqlNodeList(list, s.end(this));
    }
}

void CreateTableOption(List<SqlNode> list) :
{
    final Span s;
    final SqlIdentifier key;
    final SqlNode val;
}
{
    key = SimpleIdentifier() { s = span(); }
    <EQ>
    val = Literal()
    {
        list.add(new IgniteSqlCreateTableOption(key, val, s.end(this)));
    }
}

SqlDataTypeSpec DataTypeEx() :
{
    final SqlDataTypeSpec dt;
}
{
    (
        dt = DataType()
    |
        dt = IntervalType()
    )
    {
        return dt;
    }
}

SqlDataTypeSpec IntervalType() :
{
    final Span s;
    final SqlIntervalQualifier intervalQualifier;
}
{
    <INTERVAL> { s = span(); } intervalQualifier = IntervalQualifier() {
        return new SqlDataTypeSpec(new IgniteSqlIntervalTypeNameSpec(intervalQualifier, s.end(this)), s.pos());
    }
}

SqlTypeNameSpec UuidType(Span s) :
{
    final SqlIdentifier typeName;
}
{
    <UUID> { s = span(); typeName = new SqlIdentifier(UuidType.NAME, s.pos()); }
    {
        return new IgniteSqlTypeNameSpec(typeName, s.end(this));
    }
}


void TableElement(List<SqlNode> list) :
{
    final SqlDataTypeSpec type;
    final Boolean nullable;
    final SqlNodeList columnList;
    final Span s = Span.of();
    final ColumnStrategy strategy;
    final SqlNode dflt;
    SqlIdentifier id = null;
}
{
    id = SimpleIdentifier() type = DataTypeEx() nullable = NullableOptDefaultNull()
    (
        <DEFAULT_> { s.add(this); }
        (
            dflt = Literal()
        |
            dflt = SimpleIdentifier()
        )  {
            strategy = ColumnStrategy.DEFAULT;
        }
    |
        {
            dflt = null;
            strategy = nullable == null
                ? null
                : nullable
                    ? ColumnStrategy.NULLABLE
                    : ColumnStrategy.NOT_NULLABLE;
        }
    )
    [
        <PRIMARY> { s.add(this); } <KEY> {
            columnList = SqlNodeList.of(id);
            list.add(SqlDdlNodes.primary(s.end(columnList), null, columnList));
        }
    ]
    {
        list.add(
            SqlDdlNodes.column(s.add(id).end(this), id,
                type.withNullable(nullable), dflt, strategy));
    }
|
    [ <CONSTRAINT> { s.add(this); } id = SimpleIdentifier() ]
    <PRIMARY> { s.add(this); } <KEY>
    columnList = ParenthesizedSimpleIdentifierList() {
        list.add(SqlDdlNodes.primary(s.end(columnList), id, columnList));
    }
}

/**
* Parse a nullable option, default is null.
*/
Boolean NullableOptDefaultNull() :
{
}
{
    <NULL> { return true; }
    |
    <NOT> <NULL> { return false; }
    |
    { return null; }
}


SqlNodeList TableElementList() :
{
    final Span s;
    final List<SqlNode> list = new ArrayList<SqlNode>();
}
{
    <LPAREN> { s = span(); }
    TableElement(list)
    (
        <COMMA> TableElement(list)
    )*
    <RPAREN> {
        return new SqlNodeList(list, s.end(this));
    }
}

SqlCreate SqlCreateTable(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier id;
    final SqlNodeList columnList;
    SqlNodeList optionList = null;
    SqlNodeList colocationColumns = null;
}
{
    <TABLE>
    ifNotExists = IfNotExistsOpt()
    id = CompoundIdentifier()
    columnList = TableElementList()
    [
        <COLOCATE> [<BY>] {s.add(this);}
            colocationColumns = ParenthesizedSimpleIdentifierList()
    ]
    [
        <WITH> { s.add(this); } optionList = CreateTableOptionList()
    ]
    {
        return new IgniteSqlCreateTable(s.end(this), ifNotExists, id, columnList, colocationColumns, optionList);
    }
}

SqlNode ColumnNameWithSortDirection() :
{
    final Span s;
    SqlNode col;
}
{
    col = SimpleIdentifier()
    (
        <ASC>
    |   <DESC> {
            col = SqlStdOperatorTable.DESC.createCall(getPos(), col);
        }
    )?
    (
        LOOKAHEAD(2)
        <NULLS> <FIRST> {
            col = SqlStdOperatorTable.NULLS_FIRST.createCall(getPos(), col);
        }
    |
        <NULLS> <LAST> {
            col = SqlStdOperatorTable.NULLS_LAST.createCall(getPos(), col);
        }
    )?
    {
        return col;
    }
}

SqlNodeList ColumnNameWithSortDirectionList() :
{
    final Span s;
    final List<SqlNode> list = new ArrayList<SqlNode>();
    SqlNode col = null;
}
{
    <LPAREN> { s = span(); }
    col = ColumnNameWithSortDirection() { list.add(col); }
    (
        <COMMA> col = ColumnNameWithSortDirection() { list.add(col); }
    )*
    <RPAREN> {
        return new SqlNodeList(list, s.end(this));
    }
}

SqlNodeList ColumnNameList() :
{
    final Span s;
    final List<SqlNode> list = new ArrayList<SqlNode>();
    SqlNode col = null;
}
{
    <LPAREN> { s = span(); }
    col = SimpleIdentifier() { list.add(col); }
    (
        <COMMA> col = SimpleIdentifier() { list.add(col); }
    )*
    <RPAREN> {
        return new SqlNodeList(list, s.end(this));
    }
}

SqlCreate SqlCreateIndex(Span s, boolean replace) :
{
    final boolean ifNotExists;
    final SqlIdentifier idxId;
    final SqlIdentifier tblId;
    final SqlNodeList columnList;
    IgniteSqlIndexType type = IgniteSqlIndexType.IMPLICIT_TREE;
}
{
    <INDEX>
    ifNotExists = IfNotExistsOpt()
    idxId = SimpleIdentifier()
    <ON>
    tblId = CompoundIdentifier()
    (
        columnList = ColumnNameWithSortDirectionList()
    |
        LOOKAHEAD(2)
        <USING> <TREE> {
            s.add(this);

            type = IgniteSqlIndexType.TREE;
        }

        columnList = ColumnNameWithSortDirectionList()
    |
        <USING> <HASH> {
            s.add(this);

            type = IgniteSqlIndexType.HASH;
        }

        columnList = ColumnNameList()
    ) {
        return new IgniteSqlCreateIndex(s.end(this), ifNotExists, idxId, tblId, type, columnList);
    }
}

boolean IfExistsOpt() :
{
}
{
    <IF> <EXISTS> { return true; }
|
    { return false; }
}

SqlDrop SqlDropTable(Span s, boolean replace) :
{
    final boolean ifExists;
    final SqlIdentifier id;
}
{
    <TABLE> ifExists = IfExistsOpt() id = CompoundIdentifier() {
        return SqlDdlNodes.dropTable(s.end(this), ifExists, id);
    }
}

SqlDrop SqlDropIndex(Span s, boolean replace) :
{
    final boolean ifExists;
    final SqlIdentifier idxId;
}
{
    <INDEX> ifExists = IfExistsOpt() idxId = CompoundIdentifier() {
        return new IgniteSqlDropIndex(s.end(this), ifExists, idxId);
    }
}

void InfixCast(List<Object> list, ExprContext exprContext, Span s) :
{
    final SqlDataTypeSpec dt;
}
{
    <INFIX_CAST> {
        checkNonQueryExpression(exprContext);
    }
    dt = DataTypeEx() {
        list.add(
            new SqlParserUtil.ToTreeListItem(SqlLibraryOperators.INFIX_CAST,
                s.pos()));
        list.add(dt);
    }
}

SqlNodeList ColumnWithTypeList() :
{
    final Span s;
    List<SqlNode> list = new ArrayList<SqlNode>();
    SqlNode col;
}
{
    <LPAREN> { s = span(); }
    col = ColumnWithType() { list.add(col); }
    (
        <COMMA> col = ColumnWithType() { list.add(col); }
    )*
    <RPAREN> {
        return new SqlNodeList(list, s.end(this));
    }
}

SqlNode ColumnWithType() :
{
    SqlIdentifier id;
    SqlDataTypeSpec type;
    boolean nullable = true;
    final ColumnStrategy strategy;
    final SqlNode dflt;
    final Span s = Span.of();
}
{
    id = SimpleIdentifier()
    type = DataTypeEx()
    [
        <NOT> <NULL> {
            nullable = false;
        }
    ]
    (
        <DEFAULT_> { s.add(this); } dflt = Literal() {
            strategy = ColumnStrategy.DEFAULT;
        }
    |
    {
            dflt = null;
            strategy = nullable ? ColumnStrategy.NULLABLE
                : ColumnStrategy.NOT_NULLABLE;
        }
    )
    {
        return SqlDdlNodes.column(s.add(id).end(this), id, type.withNullable(nullable), dflt, strategy);
    }
}

SqlNodeList ColumnWithTypeOrList() :
{
    SqlNode col;
    SqlNodeList list;
}
{
    col = ColumnWithType() { return new SqlNodeList(Collections.singletonList(col), col.getParserPosition()); }
|
    list = ColumnWithTypeList() { return list; }
}

SqlNode SqlAlterTable() :
{
    final Span s;
    final boolean ifExists;
    final SqlIdentifier id;
    boolean colIgnoreErr;
    SqlNode col;
    SqlNodeList cols;
}
{
    <ALTER> { s = span(); }
    <TABLE> ifExists = IfExistsOpt() id = CompoundIdentifier()
    (
        <ADD> [<COLUMN>] cols = ColumnWithTypeOrList() {
            return new IgniteSqlAlterTableAddColumn(s.end(this), ifExists, id, cols);
        }
    |
        <DROP> [<COLUMN>] cols = SimpleIdentifierOrList() {
            return new IgniteSqlAlterTableDropColumn(s.end(this), ifExists, id, cols);
        }
    |
        <ALTER> [<COLUMN>] {
            return SqlAlterColumn(s, id, ifExists);
        }
    )
}

SqlNode SqlAlterColumn(Span s, SqlIdentifier tableId, boolean ifExists) :
{
    SqlIdentifier id;
    SqlDataTypeSpec type;
    Boolean nullable;
    SqlNode dflt;
}
{
    id = SimpleIdentifier()
    (
        LOOKAHEAD(2)
        <SET> <DATA> <TYPE> { s.add(this); } type = DataTypeEx() nullable = NullableOptDefaultNull() dflt = DefaultLiteralOrNull() {
            return new IgniteSqlAlterColumn(s.end(this), ifExists, tableId, id, type, dflt, nullable == null ? null : !nullable);
        }
    |
        LOOKAHEAD(2)
        <SET> <NOT> <NULL> {
            return new IgniteSqlAlterColumn(s.end(this), ifExists, tableId, id, null, null, true);
        }
    |
        LOOKAHEAD(2)
        <DROP> <NOT> <NULL> {
            return new IgniteSqlAlterColumn(s.end(this), ifExists, tableId, id, null, null, false);
        }
    |
        <SET> <DEFAULT_> { s.add(this); } dflt = Literal()
        {
            return new IgniteSqlAlterColumn(s.end(this), ifExists, tableId, id, null, dflt, null);
        }
    |
        <DROP> <DEFAULT_> {
            return new IgniteSqlAlterColumn(s.end(this), ifExists, tableId, id, null, SqlLiteral.createNull(s.end(this)), null);
        }
    )
}

SqlNode DefaultLiteralOrNull() :
{
    SqlNode dflt;
}
{
    <DEFAULT_> dflt = Literal()
    {
        return dflt;
    }
    |
    {
        return null;
    }
}

<DEFAULT, DQID, BTID> TOKEN :
{
< NEGATE: "!" >
|   < TILDE: "~" >
}

SqlCreate SqlCreateZone(Span s, boolean replace) :
{
        final boolean ifNotExists;
        final SqlIdentifier id;
        SqlNodeList optionList = null;
        SqlIdentifier engine = null;
}
{
    <ZONE> { s.add(this); }
        ifNotExists = IfNotExistsOpt()
        id = CompoundIdentifier()
    [
            <ENGINE> { s.add(this); } engine = SimpleIdentifier()
    ]
    [
        <WITH> { s.add(this); } optionList = CreateZoneOptionList()
    ]
    {
        return new IgniteSqlCreateZone(s.end(this), ifNotExists, id, optionList, engine);
    }
}

SqlNodeList CreateZoneOptionList() :
{
    List<SqlNode> list = new ArrayList<SqlNode>();
    final Span s = Span.of();
}
{
    CreateZoneOption(list)
    (
        <COMMA> { s.add(this); } CreateZoneOption(list)
    )*
    {
        return new SqlNodeList(list, s.end(this));
    }
}

void CreateZoneOption(List<SqlNode> list) :
{
    final Span s;
    final SqlIdentifier key;
    final SqlNode val;
}
{
    key = SimpleIdentifier() { s = span(); }
    <EQ>
    val = Literal()
    {
        list.add(new IgniteSqlZoneOption(key, val, s.end(this)));
    }
}

SqlDrop SqlDropZone(Span s, boolean replace) :
{
    final boolean ifExists;
    final SqlIdentifier zoneId;
}
{
    <ZONE> ifExists = IfExistsOpt() zoneId = CompoundIdentifier() {
        return new IgniteSqlDropZone(s.end(this), ifExists, zoneId);
    }
}

SqlNode SqlAlterZone() :
{
    final Span s;
    final SqlIdentifier zoneId;
    final boolean ifExists;
    final SqlIdentifier newZoneId;
    SqlNodeList optionList = null;
}
{
    <ALTER> { s = span(); }
    <ZONE>
    ifExists = IfExistsOpt()
    zoneId = CompoundIdentifier()

    (
      <RENAME> <TO> newZoneId = SimpleIdentifier() {
        return new IgniteSqlAlterZoneRenameTo(s.end(this), zoneId, newZoneId, ifExists);
      }
      |
      <SET> { s.add(this); } optionList = AlterZoneOptions() {
        return new IgniteSqlAlterZoneSet(s.end(this), zoneId, optionList, ifExists);
      }
    )
}

SqlNodeList AlterZoneOptions() :
{
  List<SqlNode> list = new ArrayList<SqlNode>();
  final Span s = Span.of();
}
{
  AlterZoneOption(list)
  (
      <COMMA> { s.add(this); } AlterZoneOption(list)
  )*
  {
      return new SqlNodeList(list, s.end(this));
  }
}

void AlterZoneOption(List<SqlNode> list) :
{
    final Span s;
    final SqlIdentifier key;
    final SqlNode val;
}
{
  key = SimpleIdentifier() { s = span(); }
  <EQ>
  val = Literal()
  {
      list.add(new IgniteSqlZoneOption(key, val, s.end(this)));
  }
}

SqlLiteral ParseDecimalLiteral():
{
    final BigDecimal value;
}
{
  <DECIMAL> <QUOTED_STRING> {
    value = IgniteSqlParserUtil.parseDecimal(token.image, getPos());
    return IgniteSqlDecimalLiteral.create(value, getPos());
  }
}

/**
* Parse datetime types: date, time, timestamp.
*
* TODO Method doesn't recognize '*_WITH_LOCAL_TIME_ZONE' types and should be removed after IGNITE-19274.
*/
SqlTypeNameSpec IgniteDateTimeTypeName() :
{
    int precision = -1;
    SqlTypeName typeName;
    final Span s;
}
{
    <DATE> {
        typeName = SqlTypeName.DATE;
        return new SqlBasicTypeNameSpec(typeName, getPos());
    }
|
    <TIME> { s = span(); }
    precision = PrecisionOpt()
    {
        typeName = SqlTypeName.TIME;
        return new SqlBasicTypeNameSpec(typeName, precision, s.end(this));
    }
|
    <TIMESTAMP> { s = span(); }
    precision = PrecisionOpt()
    {
        typeName = SqlTypeName.TIMESTAMP;
        return new SqlBasicTypeNameSpec(typeName, precision, s.end(this));
    }
}
