package com.bretpatterson.schemagen.graphql;

import com.bretpatterson.schemagen.graphql.annotations.GraphQLDataFetcher;
import com.google.common.base.Optional;
import graphql.schema.DataFetcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Interface that allows you to control the instantiation of all custom DataFetchers. This allows you to initialize
 * the datafetchers with any necessary contextual information.
 */
public interface IDataFetcherFactory {

	/**
	 * Factory for creating data fetchers for Field definitions annotated with {@link GraphQLDataFetcher}.
	 * @param objectMapper the {@link IGraphQLObjectMapper}
	 * @param field      the Field object itself
	 * @param dataFetcher
	 * @return
	 */
	DataFetcher newFieldDataFetcher(IGraphQLObjectMapper objectMapper, Optional<Object> targetObject, Field field, String fieldName, Class<? extends DataFetcher> dataFetcher);

	/**
	 * Factory for creating data fetchers for Method definitions annotated with {@link GraphQLDataFetcher}.
	 *
	 * @param objectMapper
	 * @param targetObject
	 * @param method
	 * @param fieldName
	 * @param dataFetcher
	 * @return
	 */
	DataFetcher newMethodDataFetcher(IGraphQLObjectMapper objectMapper, Optional<Object> targetObject, Method method, String fieldName, Class<? extends DataFetcher> dataFetcher);

}
