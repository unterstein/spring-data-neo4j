/*
 * Copyright (c)  [2011-2015] "Pivotal Software, Inc." / "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.springframework.data.neo4j.repository.support;

import java.io.Serializable;

import org.neo4j.ogm.session.Session;
import org.springframework.data.neo4j.mapping.Neo4jMappingContext;
import org.springframework.data.neo4j.repository.GraphRepositoryImpl;
import org.springframework.data.neo4j.repository.query.GraphQueryLookupStrategy;
import org.springframework.data.repository.core.EntityInformation;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.data.repository.query.EvaluationContextProvider;
import org.springframework.data.repository.query.QueryLookupStrategy;

/**
 * @author Vince Bickers
 */
public class GraphRepositoryFactory extends RepositoryFactorySupport {

    private final Session session;
    private final Neo4jMappingContext mappingContext;

    public GraphRepositoryFactory(Session session, Neo4jMappingContext mappingContext) {
        this.session = session;
        this.mappingContext = mappingContext;
    }

    @Override
    public <T, ID extends Serializable> EntityInformation<T, ID> getEntityInformation(Class<T> type) {
        return new GraphEntityInformation(type);
    }

    @Override
    protected Object getTargetRepository(RepositoryInformation information) {
        return getTargetRepositoryViaReflection(information, information.getDomainType(), session);
    }

    @Override
    protected Class<?> getRepositoryBaseClass(RepositoryMetadata repositoryMetadata) {
        return GraphRepositoryImpl.class;
    }

    @Override
    protected QueryLookupStrategy getQueryLookupStrategy(QueryLookupStrategy.Key key,
                                                         EvaluationContextProvider evaluationContextProvider) {
        return new GraphQueryLookupStrategy(session, key, evaluationContextProvider, mappingContext);
    }

}
