/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.infra.binder.statement.dml;

import com.cedarsoftware.util.CaseInsensitiveMap;
import lombok.SneakyThrows;
import org.apache.shardingsphere.infra.binder.segment.combine.CombineSegmentBinder;
import org.apache.shardingsphere.infra.binder.segment.from.SimpleTableSegmentBinderContext;
import org.apache.shardingsphere.infra.binder.segment.from.TableSegmentBinder;
import org.apache.shardingsphere.infra.binder.segment.from.TableSegmentBinderContext;
import org.apache.shardingsphere.infra.binder.segment.lock.LockSegmentBinder;
import org.apache.shardingsphere.infra.binder.segment.projection.ProjectionsSegmentBinder;
import org.apache.shardingsphere.infra.binder.segment.where.WhereSegmentBinder;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementBinder;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementBinderContext;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.sql.parser.sql.common.segment.dml.expr.complex.CommonTableExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.common.segment.generic.table.TableSegment;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.GenericSelectStatement;
import org.apache.shardingsphere.sql.parser.sql.dialect.handler.dml.SelectStatementHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Select statement binder.
 */
public final class SelectStatementBinder implements SQLStatementBinder<GenericSelectStatement> {
    
    @SneakyThrows
    @Override
    public GenericSelectStatement bind(final GenericSelectStatement sqlStatement, final ShardingSphereMetaData metaData, final String defaultDatabaseName) {
        return bind(sqlStatement, metaData, defaultDatabaseName, Collections.emptyMap(), Collections.emptyMap());
    }
    
    @SneakyThrows
    private GenericSelectStatement bind(final GenericSelectStatement sqlStatement, final ShardingSphereMetaData metaData, final String defaultDatabaseName,
                                        final Map<String, TableSegmentBinderContext> outerTableBinderContexts, final Map<String, TableSegmentBinderContext> externalTableBinderContexts) {
        GenericSelectStatement result = sqlStatement.getClass().getDeclaredConstructor().newInstance();
        Map<String, TableSegmentBinderContext> tableBinderContexts = new LinkedHashMap<>();
        SQLStatementBinderContext statementBinderContext = new SQLStatementBinderContext(metaData, defaultDatabaseName, sqlStatement.getDatabaseType(), sqlStatement.getVariableNames());
        statementBinderContext.getExternalTableBinderContexts().putAll(externalTableBinderContexts);
        
        SelectStatementHandler.getWithSegment(sqlStatement).ifPresent(optional -> {
            Map<String, TableSegmentBinderContext> withExternalTableBinderContexts = new CaseInsensitiveMap<>(externalTableBinderContexts);
            for (CommonTableExpressionSegment tableExpressionSegment : optional.getCommonTableExpressions()) {
                SelectStatement subqueryStatement = tableExpressionSegment.getSubquery().getSelect();
                SelectStatement boundedSelectStatement = SelectStatementBinder.this.bind(subqueryStatement, metaData, defaultDatabaseName, tableBinderContexts, withExternalTableBinderContexts);
                withExternalTableBinderContexts.put(tableExpressionSegment.getIdentifier().getValue(), new SimpleTableSegmentBinderContext(boundedSelectStatement.getProjections().getProjections()));
            }
            statementBinderContext.getExternalTableBinderContexts().putAll(withExternalTableBinderContexts);
            SelectStatementHandler.setWithSegment(result, optional);
        });
        
        TableSegment boundedTableSegment = TableSegmentBinder.bind(sqlStatement.getFrom(), statementBinderContext, tableBinderContexts, outerTableBinderContexts);
        result.setFrom(boundedTableSegment);
        result.setProjections(ProjectionsSegmentBinder.bind(sqlStatement.getProjections(), statementBinderContext, boundedTableSegment, tableBinderContexts, outerTableBinderContexts));
        sqlStatement.getWhere().ifPresent(optional -> result.setWhere(WhereSegmentBinder.bind(optional, statementBinderContext, tableBinderContexts, outerTableBinderContexts)));
        // TODO support other segment bind in select statement
        sqlStatement.getGroupBy().ifPresent(result::setGroupBy);
        sqlStatement.getHaving().ifPresent(result::setHaving);
        sqlStatement.getOrderBy().ifPresent(result::setOrderBy);
        sqlStatement.getCombine().ifPresent(optional -> result.setCombine(CombineSegmentBinder.bind(optional, statementBinderContext)));
        SelectStatementHandler.getLimitSegment(sqlStatement).ifPresent(optional -> SelectStatementHandler.setLimitSegment(result, optional));
        SelectStatementHandler.getLockSegment(sqlStatement)
                .ifPresent(optional -> SelectStatementHandler.setLockSegment(result, LockSegmentBinder.bind(optional, statementBinderContext, tableBinderContexts, outerTableBinderContexts)));
        SelectStatementHandler.getWindowSegment(sqlStatement).ifPresent(optional -> SelectStatementHandler.setWindowSegment(result, optional));
        SelectStatementHandler.getModelSegment(sqlStatement).ifPresent(optional -> SelectStatementHandler.setModelSegment(result, optional));
        result.addParameterMarkerSegments(sqlStatement.getParameterMarkerSegments());
        result.getCommentSegments().addAll(sqlStatement.getCommentSegments());
        return result;
    }
    
    /**
     * Bind correlate subquery select statement.
     *
     * @param sqlStatement                subquery select statement
     * @param metaData                    meta data
     * @param defaultDatabaseName         default database name
     * @param outerTableBinderContexts    outer select statement table binder contexts
     * @param externalTableBinderContexts external table binder contexts
     * @return bounded correlate subquery select statement
     */
    @SneakyThrows
    public GenericSelectStatement bindCorrelateSubquery(final GenericSelectStatement sqlStatement, final ShardingSphereMetaData metaData, final String defaultDatabaseName,
                                                        final Map<String, TableSegmentBinderContext> outerTableBinderContexts,
                                                        final Map<String, TableSegmentBinderContext> externalTableBinderContexts) {
        return bind(sqlStatement, metaData, defaultDatabaseName, outerTableBinderContexts, externalTableBinderContexts);
    }
    
    /**
     * Bind with external table contexts.
     *
     * @param statement             select statement
     * @param metaData              meta data
     * @param defaultDatabaseName   default database name
     * @param externalTableContexts external table contexts
     * @return select statement
     */
    public GenericSelectStatement bindWithExternalTableContexts(final GenericSelectStatement statement, final ShardingSphereMetaData metaData, final String defaultDatabaseName,
                                                                final Map<String, TableSegmentBinderContext> externalTableContexts) {
        return bind(statement, metaData, defaultDatabaseName, Collections.emptyMap(), externalTableContexts);
    }
}
