/*
 * Copyright (c)  [2011-2015] "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.session;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.apache.http.impl.client.CloseableHttpClient;
import org.neo4j.ogm.annotation.Property;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.RelationshipEntity;
import org.neo4j.ogm.cypher.Parameter;
import org.neo4j.ogm.cypher.compiler.CypherContext;
import org.neo4j.ogm.cypher.query.GraphModelQuery;
import org.neo4j.ogm.cypher.query.GraphRowModelQuery;
import org.neo4j.ogm.cypher.query.RowModelQuery;
import org.neo4j.ogm.cypher.query.RowModelQueryWithStatistics;
import org.neo4j.ogm.cypher.statement.ParameterisedStatement;
import org.neo4j.ogm.entityaccess.FieldWriter;
import org.neo4j.ogm.mapper.EntityGraphMapper;
import org.neo4j.ogm.mapper.MappingContext;
import org.neo4j.ogm.metadata.MetaData;
import org.neo4j.ogm.metadata.RelationshipUtils;
import org.neo4j.ogm.metadata.info.AnnotationInfo;
import org.neo4j.ogm.metadata.info.ClassInfo;
import org.neo4j.ogm.metadata.info.FieldInfo;
import org.neo4j.ogm.model.GraphModel;
import org.neo4j.ogm.session.request.DefaultRequest;
import org.neo4j.ogm.session.request.Neo4jRequest;
import org.neo4j.ogm.session.request.RequestHandler;
import org.neo4j.ogm.session.request.SessionRequestHandler;
import org.neo4j.ogm.session.request.strategy.*;
import org.neo4j.ogm.session.response.Neo4jResponse;
import org.neo4j.ogm.session.response.ResponseHandler;
import org.neo4j.ogm.session.response.SessionResponseHandler;
import org.neo4j.ogm.session.result.GraphRowModel;
import org.neo4j.ogm.session.result.QueryStatistics;
import org.neo4j.ogm.session.result.RowModel;
import org.neo4j.ogm.session.transaction.SimpleTransaction;
import org.neo4j.ogm.session.transaction.Transaction;
import org.neo4j.ogm.session.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Vince Bickers
 * @author Luanne Misquitta
 */
public class Neo4jSession implements Session {

    private final Logger logger = LoggerFactory.getLogger(Neo4jSession.class);

    private final MetaData metaData;
    private final MappingContext mappingContext;
    private final ObjectMapper mapper;
    private final String autoCommitUrl;
    private final TransactionManager txManager;

    private Neo4jRequest<String> request;

    private static final Pattern WRITE_CYPHER_KEYWORDS = Pattern.compile("\\b(CREATE|MERGE|SET|DELETE|REMOVE)\\b");

    public Neo4jSession(MetaData metaData, String url, CloseableHttpClient client, ObjectMapper mapper) {
        this.metaData = metaData;
        this.mapper = mapper;
        this.mappingContext = new MappingContext(metaData);
        this.txManager = new TransactionManager(client, url);
        this.autoCommitUrl = autoCommit(url);
        this.request = new DefaultRequest(client);
    }

    public void setRequest(Neo4jRequest<String> neo4jRequest) {
        this.request=neo4jRequest;
    }

    private RequestHandler getRequestHandler() {
        return new SessionRequestHandler(mapper, request);
    }

    private ResponseHandler getResponseHandler() {
        return new SessionResponseHandler(metaData, mappingContext);
    }

    @Override
    public <T> T load(Class<T> type, Long id) {
        return load(type, id, 1);
    }

    @Override
    public <T> T load(Class<T> type, Long id, int depth) {
        String url = getCurrentOrCreateAutocommitTransaction().url();
        QueryStatements queryStatements = getQueryStatementsBasedOnType(type);
        GraphModelQuery qry = queryStatements.findOne(id, depth);
        try (Neo4jResponse<GraphModel> response = getRequestHandler().execute(qry, url)) {
            return getResponseHandler().loadById(type, response, id);
        }
    }

    @Override
    public <T> Collection<T> loadAll(Class<T> type, Collection<Long> ids) {
        return loadAll(type, ids, 1);
    }

    @Override
    public <T> Collection<T> loadAll(Class<T> type, Collection<Long> ids, int depth) {
        String url = getCurrentOrCreateAutocommitTransaction().url();
        QueryStatements queryStatements = getQueryStatementsBasedOnType(type);
        GraphModelQuery qry = queryStatements.findAll(ids, depth);
        try (Neo4jResponse<GraphModel> response = getRequestHandler().execute(qry, url)) {
            return getResponseHandler().loadAll(type, response);
        }
    }

    @Override
    public <T> Collection<T> loadAll(Class<T> type) {
        return loadAll(type, 1);
    }

    @Override
    public <T> Collection<T> loadAll(Class<T> type, int depth) {
        ClassInfo classInfo = metaData.classInfo(type.getName());
        String url = getCurrentOrCreateAutocommitTransaction().url();
        QueryStatements queryStatements = getQueryStatementsBasedOnType(type);
        GraphModelQuery qry = queryStatements.findByType(getEntityType(classInfo), depth);
        try (Neo4jResponse<GraphModel> response = getRequestHandler().execute(qry, url)) {
            return getResponseHandler().loadAll(type, response);
        }
    }

    @Override
    public <T> Collection<T> loadAll(Collection<T> objects) {
        return loadAll(objects, 1);
    }

    @Override
    public <T> Collection<T> loadAll(Collection<T> objects, int depth) {

        if (objects == null || objects.isEmpty()) {
            return objects;
        }

        Set<Long> ids = new HashSet<>();
        Class type = objects.iterator().next().getClass();
        ClassInfo classInfo = metaData.classInfo(type.getName());
        Field identityField = classInfo.getField(classInfo.identityField());
        for (Object o: objects) {
            ids.add((Long) FieldWriter.read(identityField, o));
        }
        return loadAll(type, ids, depth);
    }

    @Override
    public <T> Collection<T> loadByProperty(Class<T> type, Parameter property) {
        return loadByProperties(type, Collections.singletonList(property));
    }

    @Override
    public <T> Collection<T> loadByProperty(Class<T> type, Parameter property, int depth) {
       return loadByProperties(type, Collections.singletonList(property), depth);
    }

    @Override
    public <T> Collection<T> loadByProperties(Class<T> type, List<Parameter> properties) {
        return loadByProperties(type, properties, 1);
    }

    @Override
    public <T> Collection<T> loadByProperties(Class<T> type, List<Parameter> properties, int depth) {
        ClassInfo classInfo = metaData.classInfo(type.getName());
        String url = getCurrentOrCreateAutocommitTransaction().url();
        QueryStatements queryStatements = getQueryStatementsBasedOnType(type);
        GraphRowModelQuery qry = queryStatements.findByProperties(getEntityType(classInfo), resolvePropertyAnnotations(type, properties), depth);



        try (Neo4jResponse<GraphRowModel> response = getRequestHandler().execute(qry, url)) {
            return getResponseHandler().loadByProperty(type, response);
        }
    }

    @Override
    public Transaction beginTransaction() {

        logger.info("beginTransaction() being called on thread: " + Thread.currentThread().getId());
        logger.info("Neo4jSession identity: " + this);

        Transaction tx = txManager.openTransaction(mappingContext);

        logger.info("Obtained new transaction: " + tx.url() + ", tx id: " + tx);
        return tx;
    }

    @Override
    public <T> T queryForObject(Class<T> type, String cypher, Map<String, ?> parameters) {
        Iterable<T> results = query(type, cypher, parameters);

        int resultSize = Utils.size(results);

        if (resultSize < 1 ) {
            return null;
        }

        if (resultSize > 1) {
            throw new RuntimeException("Result not of expected size. Expected 1 row but found " + resultSize);
        }

        return results.iterator().next();
    }

    @Override
    public Iterable<Map<String, Object>> query(String cypher, Map<String, ?> parameters) {
        return executeAndMap(null, cypher, parameters, new MapRowModelMapper());
    }

    @Override
    public <T> Iterable<T> query(Class<T> type, String cypher, Map<String, ?> parameters) {
        if (type == null || type.equals(Void.class)) {
            throw new RuntimeException("Supplied type must not be null or void.");
        }
        return executeAndMap(type, cypher, parameters, new EntityRowModelMapper<T>());
    }

    private <T> Iterable<T> executeAndMap(Class<T> type, String cypher, Map<String, ?> parameters, RowModelMapper<T> rowModelMapper) {
        if (StringUtils.isEmpty(cypher)) {
            throw new RuntimeException("Supplied cypher statement must not be null or empty.");
        }

        if (parameters == null) {
            throw new RuntimeException("Supplied Parameters cannot be null.");
        }

        assertReadOnly(cypher);

        String url = getCurrentOrCreateAutocommitTransaction().url();

        if (type != null && metaData.classInfo(type.getSimpleName()) != null) {
            GraphModelQuery qry = new GraphModelQuery(cypher, parameters);
            try (Neo4jResponse<GraphModel> response = getRequestHandler().execute(qry, url)) {
                return getResponseHandler().loadAll(type, response);
            }
        } else {
            RowModelQuery qry = new RowModelQuery(cypher, parameters);
            try (Neo4jResponse<RowModel> response = getRequestHandler().execute(qry, url)) {

                String[] variables = response.columns();

                Collection<T> result = new ArrayList<>();
                RowModel rowModel;
                while ((rowModel = response.next()) != null) {
                    rowModelMapper.mapIntoResult(result, rowModel.getValues(), variables);
                }

                return result;
            }
        }
    }

    private void assertReadOnly(String cypher) {
        Matcher matcher = WRITE_CYPHER_KEYWORDS.matcher(cypher.toUpperCase());

        if (matcher.find()) {
            throw new RuntimeException("query() only allows read only cypher. To make modifications use execute()");
        }
    }

    @Override
    public QueryStatistics execute(String cypher, Map<String, Object> parameters) {
        if (StringUtils.isEmpty(cypher)) {
            throw new RuntimeException("Supplied cypher statement must not be null or empty.");
        }

        if (parameters == null) {
            throw new RuntimeException("Supplied Parameters cannot be null.");
        }
        assertNothingReturned(cypher);
        String url  = getCurrentOrCreateAutocommitTransaction().url();
        // NOTE: No need to check if domain objects are parameters and flatten them to json as this is done
        // for us using the existing execute() method.
        RowModelQueryWithStatistics parameterisedStatement = new RowModelQueryWithStatistics(cypher, parameters);
        try (Neo4jResponse<QueryStatistics> response = getRequestHandler().execute(parameterisedStatement, url)) {
            return response.next();
        }
    }

    @Override
    public <T> T doInTransaction(GraphCallback<T> graphCallback) {
        return graphCallback.apply(getRequestHandler(), getCurrentOrCreateAutocommitTransaction(), this.metaData);
    }

    @Override
    public QueryStatistics execute(String statement) {
        if (StringUtils.isEmpty(statement)) {
            throw new RuntimeException("Supplied cypher statement must not be null or empty.");
        }
        assertNothingReturned(statement);
        RowModelQueryWithStatistics parameterisedStatement = new RowModelQueryWithStatistics(statement, Utils.map());
        String url = getCurrentOrCreateAutocommitTransaction().url();
        try (Neo4jResponse<QueryStatistics> response = getRequestHandler().execute(parameterisedStatement, url)) {
            return response.next();
        }
    }

    @Override
    public void purgeDatabase() {
        String url = getCurrentOrCreateAutocommitTransaction().url();
        getRequestHandler().execute(new DeleteNodeStatements().purge(), url).close();
        mappingContext.clear();
    }

    @Override
    public void clear() {
        mappingContext.clear();
    }

    @Override
    public <T> void save(T object) {
        if (object.getClass().isArray() || Iterable.class.isAssignableFrom(object.getClass())) {
            saveAll(object, -1);
        } else {
            save(object, -1); // default : full tree of changed objects
        }
    }

    private <T> void saveAll(T object, int depth) {
        List<T> list;
        if (object.getClass().isArray()) {
            list = Arrays.asList(object);
        } else {
            list = (List<T>) object;
        }
        for (T element : list) {
            save(element, depth);
        }
    }

    private <T> void deleteAll(T object) {
        List<T> list;
        if (object.getClass().isArray()) {
            list = Arrays.asList(object);
        } else {
            list = (List<T>) object;
        }
        for (T element : list) {
            delete(element);
        }
    }

    @Override
    public <T> void save(T object, int depth) {
        if (object.getClass().isArray() || Iterable.class.isAssignableFrom(object.getClass())) {
            saveAll(object, depth);
        } else {
            ClassInfo classInfo = metaData.classInfo(object);
            if (classInfo != null) {
                Transaction tx = getCurrentOrCreateAutocommitTransaction();
                CypherContext context = new EntityGraphMapper(metaData, mappingContext).map(object, depth);
                try (Neo4jResponse<String> response = getRequestHandler().execute(context.getStatements(), tx.url())) {
                    getResponseHandler().updateObjects(context, response, mapper);
                    tx.append(context);
                }
            } else {
                logger.info(object.getClass().getName() + " is not an instance of a persistable class");
            }
        }
    }

    @Override
    public <T> void delete(T object) {
        if (object.getClass().isArray() || Iterable.class.isAssignableFrom(object.getClass())) {
            deleteAll(object);
        } else {
            ClassInfo classInfo = metaData.classInfo(object);
            if (classInfo != null) {
                Field identityField = classInfo.getField(classInfo.identityField());
                Long identity = (Long) FieldWriter.read(identityField, object);
                if (identity != null) {
                    String url = getCurrentOrCreateAutocommitTransaction().url();
                    ParameterisedStatement request = getDeleteStatementsBasedOnType(object.getClass()).delete(identity);
                    try (Neo4jResponse<String> response = getRequestHandler().execute(request, url)) {
                        mappingContext.clear(object);
                    }
                }
            } else {
                logger.info(object.getClass().getName() + " is not an instance of a persistable class");
            }
        }
    }

    @Override
    public <T> void deleteAll(Class<T> type) {
        ClassInfo classInfo = metaData.classInfo(type.getName());
        if (classInfo != null) {
            String url = getCurrentOrCreateAutocommitTransaction().url();
            ParameterisedStatement request = getDeleteStatementsBasedOnType(type).deleteByType(getEntityType(classInfo));
            try (Neo4jResponse<String> response = getRequestHandler().execute(request, url)) {
                mappingContext.clear(type);
            }
        } else {
            logger.info(type.getName() + " is not a persistable class");
        }
    }

    @Override
    public Transaction getTransaction() {

        return txManager.getCurrentTransaction();

    }

    @Override
    public long countEntitiesOfType(Class<?> entity) {
        ClassInfo classInfo = metaData.classInfo(entity.getName());
        if (classInfo == null) {
            return 0;
        }

        RowModelQuery countStatement = new AggregateStatements().countNodesLabelledWith(classInfo.labels());
        String url  = getCurrentOrCreateAutocommitTransaction().url();
        try (Neo4jResponse<RowModel> response = getRequestHandler().execute(countStatement, url)) {
            RowModel queryResult = response.next();
            return queryResult == null ? 0 : ((Number) queryResult.getValues()[0]).longValue();
        }
    }

    private static String autoCommit(String url) {
        if (url == null) return url;
        if (!url.endsWith("/")) url = url + "/";
        return url + "db/data/transaction/commit";
    }

    private Transaction getCurrentOrCreateAutocommitTransaction() {

        logger.info("--------- new request ----------");
        logger.info("getOrCreateTransaction() being called on thread: " + Thread.currentThread().getId());
        logger.info("Session identity: " + this);

        Transaction tx = txManager.getCurrentTransaction();
        if (tx == null
                || tx.status().equals(Transaction.Status.CLOSED)
                || tx.status().equals(Transaction.Status.COMMITTED)
                || tx.status().equals(Transaction.Status.ROLLEDBACK)) {
            logger.info("There is no existing transaction, creating a transient one");
            return new SimpleTransaction(mappingContext, autoCommitUrl);
        }

        logger.info("Current transaction: " + tx.url() + ", tx id: " + tx);
        return tx;

    }

    private QueryStatements getQueryStatementsBasedOnType(Class type) {
        if(metaData.isRelationshipEntity(type.getName())) {
                return new VariableDepthRelationshipQuery();
        }
        //we can also use the mapping context to find the start node id, and if it exists, use it with the VariableDepthQuery
        return new VariableDepthQuery();
    }

    private DeleteStatements getDeleteStatementsBasedOnType(Class type) {
        if (metaData.isRelationshipEntity(type.getName())) {
            return new DeleteRelationshipStatements();
        }
        return new DeleteNodeStatements();
    }

    private String getEntityType(ClassInfo classInfo) {
        if(metaData.isRelationshipEntity(classInfo.name())) {
            AnnotationInfo annotation = classInfo.annotationsInfo().get(RelationshipEntity.CLASS);
               return annotation.get(RelationshipEntity.TYPE, classInfo.name());
        }
        return classInfo.label();
    }

    private List<Parameter> resolvePropertyAnnotations(Class entityType, List<Parameter> parameters) {
        for(Parameter parameter : parameters) {
            if(parameter.getOwnerEntityType() == null) {
                parameter.setOwnerEntityType(entityType);
            }
            parameter.setPropertyName(resolvePropertyName(parameter.getOwnerEntityType(), parameter.getPropertyName()));
            if(parameter.isNested()) {
                resolveRelationshipType(parameter);
                ClassInfo nestedClassInfo = metaData.classInfo(parameter.getNestedPropertyType().getName());
                parameter.setNestedEntityTypeLabel(getEntityType(nestedClassInfo));
            }
        }
        return parameters;
    }

    private String resolvePropertyName(Class entityType, String propertyName) {
        ClassInfo classInfo = metaData.classInfo(entityType.getName());
        FieldInfo fieldInfo = classInfo.propertyFieldByName(propertyName);
        if (fieldInfo != null && fieldInfo.getAnnotations() != null) {
            AnnotationInfo annotation = fieldInfo.getAnnotations().get(Property.CLASS);
            if (annotation != null) {
                return annotation.get(Property.NAME, propertyName);
            }
        }
        return propertyName;
    }

    private void resolveRelationshipType(Parameter parameter) {
        ClassInfo classInfo = metaData.classInfo(parameter.getOwnerEntityType().getName());
        FieldInfo fieldInfo = classInfo.relationshipFieldByName(parameter.getNestedPropertyName());

        String defaultRelationshipType = RelationshipUtils.inferRelationshipType(parameter.getNestedPropertyName());
        parameter.setRelationshipType(defaultRelationshipType);
        parameter.setRelationshipDirection(Relationship.UNDIRECTED);
        if(fieldInfo.getAnnotations() != null) {
            AnnotationInfo annotation = fieldInfo.getAnnotations().get(Relationship.CLASS);
            if(annotation != null) {
                parameter.setRelationshipType(annotation.get(Relationship.TYPE, defaultRelationshipType));
                parameter.setRelationshipDirection(annotation.get(Relationship.DIRECTION, Relationship.UNDIRECTED));
            }
        }
    }

    private void assertNothingReturned(String cypher) {
        if (cypher.toUpperCase().contains(" RETURN ")) {
            throw new RuntimeException("execute() must not return data. Use query() instead.");
        }
    }

    // NOT on the interface
    public MappingContext context() {
        return mappingContext;
    }
}
