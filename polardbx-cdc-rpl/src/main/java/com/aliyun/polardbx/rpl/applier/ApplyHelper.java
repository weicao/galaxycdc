/*
 *
 * Copyright (c) 2013-2021, Alibaba Group Holding Limited;
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.aliyun.polardbx.rpl.applier;

import com.aliyun.polardbx.binlog.canal.binlog.dbms.DBMSColumn;
import com.aliyun.polardbx.binlog.canal.binlog.dbms.DBMSEvent;
import com.aliyun.polardbx.binlog.canal.binlog.dbms.DBMSRowChange;
import com.aliyun.polardbx.binlog.canal.binlog.dbms.DefaultQueryLog;
import com.aliyun.polardbx.binlog.canal.core.ddl.parser.DdlResult;
import com.aliyun.polardbx.binlog.canal.core.ddl.parser.DruidDdlParser;
import com.aliyun.polardbx.rpl.common.DataSourceUtil;
import com.aliyun.polardbx.rpl.common.RplConstants;
import com.aliyun.polardbx.rpl.common.TaskContext;
import com.aliyun.polardbx.rpl.dbmeta.ColumnInfo;
import com.aliyun.polardbx.rpl.dbmeta.TableInfo;
import com.aliyun.polardbx.rpl.taskmeta.DbTaskMetaManager;
import com.aliyun.polardbx.rpl.taskmeta.DdlState;
import com.google.common.collect.Lists;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.aliyun.polardbx.binlog.CommonUtils.escape;

/**
 * @author shicai.xsc 2020/12/1 21:09
 * @since 5.0.0.0
 */
@Slf4j
@Data
public class ApplyHelper {

    private static final String INSERT_UPDATE_SQL = "INSERT INTO `%s`.`%s`(%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s";
    private static final String BATCH_INSERT_SQL = "INSERT INTO `%s`.`%s`(%s) VALUES %s";
    private static final String REPLACE_SQL = "REPLACE INTO `%s`.`%s`(%s) VALUES %s";
    private static final String INSERT_IGNORE_SQL = "INSERT IGNORE INTO `%s`.`%s`(%s) VALUES %s";
    private static final String DELETE_SQL = "DELETE FROM `%s`.`%s` WHERE %s";
    private static final String UPDATE_SQL = "UPDATE `%s`.`%s` SET %s WHERE %s";
    private static final String SELECT_SQL = "SELECT * FROM `%s`.`%s` WHERE %s";
    private static final String IF_NOT_EXISTS = " IF NOT EXISTS ";
    private static final String IF_EXISTS = " IF EXISTS ";
    private static final String CREATE_TABLE = "CREATE TABLE";
    private static final String DROP_TABLE = "DROP TALBE";
    private static final String ASYNC_DDL_HINT = "/*+TDDL:cmd_extra(PURE_ASYNC_DDL_MODE=TRUE,TSO=%s)*/";
    private static final String DDL_HINT = "/*+TDDL:cmd_extra(TSO=%s)*/";
    private static final String SHOW_FULL_DDL = "SHOW FULL DDL";
    private static final String TSO_PATTERN = "TSO=%s)";
    private static final String DDL_STMT = "DDL_STMT";
    private static final String DDL_STATE = "STATE";
    private static final String DDL_STATE_PENDING = "PENDING";

    public static boolean isDdl(DBMSEvent dbmsEvent) {
        switch (dbmsEvent.getAction()) {
        case CREATE:
        case ERASE:
        case ALTER:
        case RENAME:
        case TRUNCATE:
        case CREATEDB:
        case DROPDB:
        case CINDEX:
        case DINDEX:
            return true;
        default:
            return false;
        }
    }

    public static boolean tranExecUpdate(DataSource dataSource, List<SqlContext> sqlContexts) {
        Connection conn = null;
        List<PreparedStatement> stmts = new ArrayList<>();
        SqlContext sqlContext = null;

        try {
            conn = dataSource.getConnection();
            // start transaction
            conn.setAutoCommit(false);

            for (int i = 0; i < sqlContexts.size(); i++) {
                sqlContext = sqlContexts.get(i);
                PreparedStatement stmt = conn.prepareStatement(sqlContext.getSql());
                stmts.add(stmt);
                // set value
                int j = 1;
                for (Serializable dataValue : sqlContext.getParams()) {
                    stmt.setObject(j, dataValue);
                    j++;
                }
                // execute
                stmt.executeUpdate();
                logExecUpdateDebug(sqlContext);
            }

            // commit
            conn.commit();
            return true;
        } catch (Throwable e) {
            logExecUpdateError(sqlContext, e);
            try {
                conn.rollback();
            } catch (Throwable e1) {
                log.error("failed in tranExecUpdate, rollback failed", e1);
            }
            return false;
        } finally {
            for (PreparedStatement stmt : stmts) {
                DataSourceUtil.closeQuery(null, stmt, null);
            }
            DataSourceUtil.closeQuery(null, null, conn);
        }
    }

    public static boolean execUpdate(DataSource dataSource, SqlContext sqlContext) {
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = dataSource.getConnection();
            stmt = conn.prepareStatement(sqlContext.getSql());

            if (sqlContext.getParams() != null) {
                // set value
                int i = 1;
                for (Serializable dataValue : sqlContext.getParams()) {
                    stmt.setObject(i, dataValue);
                    i++;
                }
            }

            // execute
            stmt.executeUpdate();
            logExecUpdateDebug(sqlContext);
            return true;
        } catch (Throwable e) {
            logExecUpdateError(sqlContext, e);
            DbTaskMetaManager.updateTaskLastError(TaskContext.getInstance().getTaskId(), e.getMessage());
            return false;
        } finally {
            DataSourceUtil.closeQuery(null, stmt, conn);
        }
    }

    public static DdlState getAsyncDdlState(DataSource dataSource, String tso) throws Throwable {
        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = dataSource.getConnection();
            stmt = conn.prepareStatement(SHOW_FULL_DDL);
            rs = stmt.executeQuery(SHOW_FULL_DDL);
            while (rs.next()) {
                String ddlStmt = rs.getString(DDL_STMT);
                String tsoPattern = String.format(TSO_PATTERN, tso);
                if (ddlStmt.contains(tsoPattern)) {
                    return rs.getString(DDL_STATE).contains(DDL_STATE_PENDING) ? DdlState.FAILED : DdlState.RUNNING;
                }
            }
            // if ddl done, show full ddl will be empty
            return DdlState.SUCCEED;
        } catch (Throwable e) {
            log.error("failed in getAsyncDdlState, tso: {}", tso, e);
            throw e;
        } finally {
            DataSourceUtil.closeQuery(rs, stmt, conn);
        }
    }

    public static DdlSqlContext getDdlSqlExeContext(DefaultQueryLog queryLog, String tso) {
        DdlSqlContext sqlContext = new DdlSqlContext(queryLog.getQuery(), "", new ArrayList<>());

        String originSql = DdlHelper.getOriginSql(queryLog.getQuery());
        String sql = StringUtils.isBlank(originSql) ? queryLog.getQuery() : originSql;
        // use actual schemaName
        DdlResult result = DruidDdlParser.parse(sql, queryLog.getSchema()).get(0);
        sqlContext.setSchema(result.getSchemaName());

        switch (queryLog.getAction()) {
        case CREATE:
        case ERASE:
        case ALTER:
        case CINDEX:
        case DINDEX:
        case RENAME:
        case TRUNCATE:
            String hint = String.format(DDL_HINT, tso);
            sqlContext.setSql(hint + sql);
            break;
        case CREATEDB:
        case DROPDB:
            sqlContext.setSchema("");
            break;
        default:
            break;
        }

        return sqlContext;
    }

    public static boolean isFiltered(String columnName) {
        return StringUtils.equalsIgnoreCase(RplConstants.RDS_IMPLICIT_ID, columnName);
    }


    public static List<SqlContext> getInsertUpdateSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo) {
        List<? extends DBMSColumn> columns = rowChange.getColumns();

        int rowCount = rowChange.getRowSize();
        List<SqlContext> contexts = Lists.newArrayListWithCapacity(rowCount);

        for (int i = 1; i <= rowCount; i++) {
            // INSERT INTO t1(column1, column2) VALUES(value1, value2)
            StringBuilder nameSqlSb = new StringBuilder();
            StringBuilder valueSqlSb = new StringBuilder();
            StringBuilder updateSqlSb = new StringBuilder();
            List<Serializable> parmas = new ArrayList<>();

            Iterator<? extends DBMSColumn> it = columns.iterator();
            while (it.hasNext()) {
                DBMSColumn column = it.next();
                String repairedColumnName = repairDMLName(column.getName());
                if (isFiltered(column.getName())) {
                    nameSqlSb.setLength(nameSqlSb.length() - 1);
                    valueSqlSb.setLength(valueSqlSb.length() - 1);
                    updateSqlSb.setLength(updateSqlSb.length() - 1);
                    continue;
                }
                nameSqlSb.append(repairedColumnName);
                valueSqlSb.append("?");
                // ON DUPLICATE KEY UPDATE
                updateSqlSb.append(repairedColumnName).append("=?");
                if (it.hasNext()) {
                    nameSqlSb.append(",");
                    valueSqlSb.append(",");
                    updateSqlSb.append(",");
                }

                Serializable columnValue = rowChange.getRowValue(i, column.getName());
                parmas.add(columnValue);
            }

            parmas.addAll(parmas);
            String insertSql = String
                .format(INSERT_UPDATE_SQL,
                    dstTbInfo.getSchema(),
                    dstTbInfo.getName(),
                    nameSqlSb,
                    valueSqlSb,
                    updateSqlSb);
            SqlContext context = new SqlContext(insertSql, dstTbInfo.getName(), parmas);
            contexts.add(context);
        }

        return contexts;
    }

    public static List<SqlContext> getDeleteThenInsertSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo) {
        List<? extends DBMSColumn> columns = rowChange.getColumns();

        int rowCount = rowChange.getRowSize();
        List<SqlContext> contexts = Lists.newArrayListWithCapacity(rowCount);

        for (int i = 1; i <= rowCount; i++) {
            // WHERE {column1} = {value1} AND {column2} = {value2}
            if (!getWhereColumns(dstTbInfo).isEmpty()) {
                StringBuilder whereSqlSb = new StringBuilder();
                List<Serializable> whereParams = new ArrayList<>();
                getWhereSql(rowChange, i, dstTbInfo, whereSqlSb, whereParams);
                String deleteSql = String.format(DELETE_SQL, dstTbInfo.getSchema(), dstTbInfo.getName(), whereSqlSb);
                SqlContext context1 = new SqlContext(deleteSql, dstTbInfo.getName(), whereParams);
                contexts.add(context1);
            }

            // INSERT INTO t1(column1, column2) VALUES(value1, value2)
            StringBuilder nameSqlSb = new StringBuilder();
            StringBuilder valueSqlSb = new StringBuilder();
            List<Serializable> parmas = new ArrayList<>();

            valueSqlSb.append("(");
            Iterator<? extends DBMSColumn> it = columns.iterator();
            while (it.hasNext()) {
                DBMSColumn column = it.next();
                if (isFiltered(column.getName())) {
                    nameSqlSb.setLength(nameSqlSb.length() - 1);
                    valueSqlSb.setLength(valueSqlSb.length() - 1);
                    continue;
                }
                String repairedColumnName = repairDMLName(column.getName());
                nameSqlSb.append(repairedColumnName);
                valueSqlSb.append("?");
                // ON DUPLICATE KEY UPDATE
                if (it.hasNext()) {
                    nameSqlSb.append(",");
                    valueSqlSb.append(",");
                }

                Serializable columnValue = rowChange.getRowValue(i, column.getName());
                parmas.add(columnValue);
            }
            valueSqlSb.append(")");
            String insertSql = String
                .format(BATCH_INSERT_SQL,
                    dstTbInfo.getSchema(),
                    dstTbInfo.getName(),
                    nameSqlSb,
                    valueSqlSb);
            SqlContext context2 = new SqlContext(insertSql, dstTbInfo.getName(), parmas);
            contexts.add(context2);
        }

        return contexts;
    }


    public static MergeDmlSqlContext getMergeInsertUpdateSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo) {
        List<? extends DBMSColumn> columns = rowChange.getColumns();

        StringBuilder nameSqlSb = new StringBuilder();
        StringBuilder valueSqlSb = new StringBuilder();
        List<Serializable> parmas = new ArrayList<>();

        for (int i = 1; i <= rowChange.getRowSize(); i++) {
            // INSERT INTO t1(column1, column2) VALUES(value1, value2),(value3, value4)
            // ON DUPLICATE KEY UPDATE column1=VALUES(column1),columns2=VALUES(column2)
            if (i > 1) {
                valueSqlSb.append(",");
            }
            valueSqlSb.append("(");
            Iterator<? extends DBMSColumn> it = columns.iterator();
            while (it.hasNext()) {
                DBMSColumn column = it.next();
                String repairedColumnName = repairDMLName(column.getName());
                if (isFiltered(column.getName())) {
                    if (i == 1) {
                        nameSqlSb.setLength(nameSqlSb.length() - 1);
                    }
                    valueSqlSb.setLength(valueSqlSb.length() - 1);
                    continue;
                }
                if (i == 1) {
                    nameSqlSb.append(repairedColumnName);
                }
                valueSqlSb.append("?");

                if (it.hasNext()) {
                    if (i == 1) {
                        nameSqlSb.append(",");
                    }
                    valueSqlSb.append(",");
                }

                Serializable columnValue = rowChange.getRowValue(i, column.getName());
                parmas.add(columnValue);
            }
            valueSqlSb.append(")");
        }

        String insertSql = String
            .format(REPLACE_SQL, dstTbInfo.getSchema(), dstTbInfo.getName(), nameSqlSb, valueSqlSb);
        return new MergeDmlSqlContext(insertSql, dstTbInfo.getName(), parmas);
    }

    public static MergeDmlSqlContext getMergeInsertSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo,
                                                                  int insertMode) {
        List<? extends DBMSColumn> columns = rowChange.getColumns();
        StringBuilder nameSqlSb = new StringBuilder();
        StringBuilder valueSqlSb = new StringBuilder();
        List<Serializable> parmas = new ArrayList<>();
        for (int i = 1; i <= rowChange.getRowSize(); i++) {
            // INSERT INTO t1(column1, column2) VALUES(value1, value2),(value3, value4)
            // ON DUPLICATE KEY UPDATE column1=VALUES(column1),columns2=VALUES(column2)
            if (i > 1) {
                valueSqlSb.append(",");
            }
            valueSqlSb.append("(");
            Iterator<? extends DBMSColumn> it = columns.iterator();
            while (it.hasNext()) {
                DBMSColumn column = it.next();
                String repairedColumnName = repairDMLName(column.getName());
                if (isFiltered(column.getName())) {
                    if (i == 1) {
                        nameSqlSb.setLength(nameSqlSb.length() - 1);
                    }
                    valueSqlSb.setLength(valueSqlSb.length() - 1);
                    continue;
                }
                if (i == 1) {
                    nameSqlSb.append(repairedColumnName);
                }
                valueSqlSb.append("?");

                if (it.hasNext()) {
                    if (i == 1) {
                        nameSqlSb.append(",");
                    }
                    valueSqlSb.append(",");
                }

                Serializable columnValue = rowChange.getRowValue(i, column.getName());
                parmas.add(columnValue);
            }
            valueSqlSb.append(")");
        }
        String sql = null;
        switch (insertMode) {
        case RplConstants.INSERT_MODE_SIMPLE_INSERT_OR_DELETE:
            sql = BATCH_INSERT_SQL;
            break;
        case RplConstants.INSERT_MODE_INSERT_IGNORE:
            sql = INSERT_IGNORE_SQL;
            break;
        case RplConstants.INSERT_MODE_REPLACE:
            sql = REPLACE_SQL;
            break;
        default:
            break;
        }
        String insertSql = String
            .format(sql, dstTbInfo.getSchema(), dstTbInfo.getName(), nameSqlSb, valueSqlSb);
        return new MergeDmlSqlContext(insertSql, dstTbInfo.getName(), parmas);
    }

    public static List<SqlContext> getDeleteSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo) {
        // actually, only 1 row in a rowChange
        int rowCount = rowChange.getRowSize();
        List<SqlContext> contexts = Lists.newArrayListWithCapacity(rowCount);

        for (int i = 1; i <= rowCount; i++) {
            // WHERE {column1} = {value1} AND {column2} = {value2}
            StringBuilder whereSqlSb = new StringBuilder();
            List<Serializable> params = new ArrayList<>();
            getWhereSql(rowChange, i, dstTbInfo, whereSqlSb, params);

            String deleteSql = String.format(DELETE_SQL, dstTbInfo.getSchema(), dstTbInfo.getName(), whereSqlSb);
            SqlContext context = new SqlContext(deleteSql, dstTbInfo.getName(), params);
            contexts.add(context);
        }

        return contexts;
    }

    public static MergeDmlSqlContext getMergeDeleteSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo) {
        StringBuilder whereSqlSb = new StringBuilder();
        List<Serializable> params = new ArrayList<>();
        getWhereInSql(rowChange, dstTbInfo, whereSqlSb, params);
        String deleteSql = String.format(DELETE_SQL, dstTbInfo.getSchema(), dstTbInfo.getName(), whereSqlSb);
        return new MergeDmlSqlContext(deleteSql, dstTbInfo.getName(), params);
    }

    public static List<SqlContext> getUpdateSqlExecContext(DBMSRowChange rowChange, TableInfo dstTbInfo) {
        List<? extends DBMSColumn> changeColumns = rowChange.getChangeColumns();

        int rowCount = rowChange.getRowSize();
        List<SqlContext> contexts = Lists.newArrayListWithCapacity(rowCount);

        for (int i = 1; i <= rowCount; i++) {
            // SET {column1} = {value1}, {column2} = {value2}
            StringBuilder setSqlSb = new StringBuilder();
            List<Serializable> parmas = new ArrayList<>();

            Iterator<? extends DBMSColumn> it = changeColumns.iterator();
            while (it.hasNext()) {
                DBMSColumn changeColumn = it.next();
                String repairedColumnName = repairDMLName(changeColumn.getName());
                setSqlSb.append(repairedColumnName).append("=?");
                if (it.hasNext()) {
                    setSqlSb.append(",");
                }

                Serializable changeColumnValue = rowChange.getChangeValue(i, changeColumn.getName());
                parmas.add(changeColumnValue);
            }

            // WHERE {column1} = {value1} AND {column2} = {value2}
            StringBuilder whereSqlSb = new StringBuilder();
            List<Serializable> whereColumnValues = new ArrayList<>();
            getWhereSql(rowChange, i, dstTbInfo, whereSqlSb, whereColumnValues);

            parmas.addAll(whereColumnValues);
            String updateSql = String
                .format(UPDATE_SQL, dstTbInfo.getSchema(), dstTbInfo.getName(), setSqlSb, whereSqlSb);
            SqlContext context = new SqlContext(updateSql, dstTbInfo.getName(), parmas);
            contexts.add(context);
        }

        return contexts;
    }

    public static List<String> getWhereColumns(TableInfo tableInfo) {
        List<String> whereColumns = new ArrayList<>();
        if (tableInfo.getPks().size() > 0) {
            whereColumns.addAll(tableInfo.getPks());
        } else {
            whereColumns.addAll(tableInfo.getUks());
        }

        // 无主键表
        if (whereColumns.isEmpty()) {
            for (ColumnInfo column: tableInfo.getColumns()) {
                whereColumns.add(column.getName());
            }
            return whereColumns;
        }

        // add shard key to where sql if exists
        if (StringUtils.isNotBlank(tableInfo.getDbShardKey()) && !whereColumns.contains(tableInfo.getDbShardKey())) {
            whereColumns.add(tableInfo.getDbShardKey());
        }
        if (StringUtils.isNotBlank(tableInfo.getTbShardKey()) && !whereColumns.contains(tableInfo.getTbShardKey())) {
            whereColumns.add(tableInfo.getTbShardKey());
        }

        return whereColumns;
    }

    public static List<String> getIdentifyColumns(TableInfo tableInfo) {
        List<String> identifyColumns = getWhereColumns(tableInfo);
        if (tableInfo.getPks().size() > 0) {
            identifyColumns.addAll(tableInfo.getUks());
        }
        return identifyColumns;
    }

    private static void getWhereSql(DBMSRowChange rowChange, int rowIndex, TableInfo tableInfo,
                                    StringBuilder whereSqlSb,
                                    List<Serializable> whereColumnValues) {
        List<String> whereColumns = getWhereColumns(tableInfo);

        for (int i = 0; i < whereColumns.size(); i++) {
            String columnName = whereColumns.get(i);

            // build where sql
            Serializable whereColumnValue = rowChange.getRowValue(rowIndex, columnName);
            String repairedName = repairDMLName(columnName);
            if (whereColumnValue == null) {
                // _drds_implicit_id_ should never be null
                whereSqlSb.append(repairedName).append(" IS NULL ");
            } else {
                whereSqlSb.append(repairedName).append("=?");
            }

            if (i < whereColumns.size() - 1) {
                whereSqlSb.append(" AND ");
            }

            // fill in where column values
            if (whereColumnValue != null) {
                whereColumnValues.add(whereColumnValue);
            }
        }
    }

    private static void getWhereInSql(DBMSRowChange rowChange, TableInfo tableInfo, StringBuilder whereSqlSb,
                                      List<Serializable> whereColumnValues) {
        List<String> whereColumns = getWhereColumns(tableInfo);

        // WHERE (column1, column2) in
        whereSqlSb.append("(");
        for (int i = 0; i < whereColumns.size(); i++) {
            String columnName = whereColumns.get(i);
            String repairedName = repairDMLName(columnName);
            whereSqlSb.append(repairedName);
            if (i < whereColumns.size() - 1) {
                whereSqlSb.append(",");
            }
        }
        whereSqlSb.append(")");
        whereSqlSb.append(" in ");

        // ((column1_value1, column2_value1), (column1_value2, column2_value2))
        whereSqlSb.append("(");
        for (int i = 1; i <= rowChange.getRowSize(); i++) {
            whereSqlSb.append("(");
            for (int j = 0; j < whereColumns.size(); j++) {
                whereSqlSb.append("?");
                if (j < whereColumns.size() - 1) {
                    whereSqlSb.append(",");
                }
                String columnName = whereColumns.get(j);
                whereColumnValues.add(rowChange.getRowValue(i, columnName));
            }
            whereSqlSb.append(")");
            if (i < rowChange.getRowSize()) {
                whereSqlSb.append(",");
            }
        }
        whereSqlSb.append(")");
    }

    private static void logExecUpdateError(SqlContext sqlContext, Throwable e) {
        StringBuilder sb = new StringBuilder();
        log.error("failed in execUpdate, sql: {}, params: {}", sqlContext.getSql(), sb, e);
    }

    private static void logExecUpdateDebug(SqlContext sqlContext) {
        if (!log.isDebugEnabled()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Serializable p : sqlContext.getParams()) {
            if (p == null) {
                sb.append("null-value").append(RplConstants.COMMA);
            } else {
                sb.append(p).append(RplConstants.COMMA);
            }
        }
        log.info("execUpdate, sql: {}, params: {}", sqlContext.getSql(), sb);
    }

    private static String repairDMLName(String name) {
        return "`" + escape(name) + "`";
    }
}
