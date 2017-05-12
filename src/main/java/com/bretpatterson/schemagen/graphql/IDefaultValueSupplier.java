package com.bretpatterson.schemagen.graphql;

import java.lang.reflect.Type;

/**
 * Interface for specifying default values for missing parameters
 */
public interface IDefaultValueSupplier {

	/**
	 * The default value to use when no value is supplied for input.
	 * 
	 * @param objectMapper the mapper needing the default value
	 * @param type the type of the parameter
	 * @return a value compatible with type, or null
	 */
	Object getDefaultValue(IGraphQLObjectMapper objectMapper, Type type);

}
