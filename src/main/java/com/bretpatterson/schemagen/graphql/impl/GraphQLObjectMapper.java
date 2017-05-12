package com.bretpatterson.schemagen.graphql.impl;

import com.bretpatterson.schemagen.graphql.IDataFetcherFactory;
import com.bretpatterson.schemagen.graphql.IGraphQLObjectMapper;
import com.bretpatterson.schemagen.graphql.IGraphQLTypeCache;
import com.bretpatterson.schemagen.graphql.IQueryFactory;
import com.bretpatterson.schemagen.graphql.ITypeNamingStrategy;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLController;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLDataFetcher;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLDeprecated;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLDescription;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLIgnore;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLMutation;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLName;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLParam;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLQuery;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLTypeConverter;
import com.bretpatterson.schemagen.graphql.annotations.GraphQLTypeMapper;
import com.bretpatterson.schemagen.graphql.ITypeFactory;
import com.bretpatterson.schemagen.graphql.datafetchers.DefaultMethodDataFetcher;
import com.bretpatterson.schemagen.graphql.datafetchers.DefaultTypeConverter;
import com.bretpatterson.schemagen.graphql.datafetchers.IDataFetcher;
import com.bretpatterson.schemagen.graphql.exceptions.NotMappableException;
import com.bretpatterson.schemagen.graphql.relay.INode;
import com.bretpatterson.schemagen.graphql.typemappers.IGraphQLTypeMapper;
import com.bretpatterson.schemagen.graphql.utils.AnnotationUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import graphql.Scalars;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.TypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;


/**
 * This is the meat of the schema gen package. Utilizing the configured properties it will traverse the objects provided and generate a type
 * hierarchy for GraphQL.
 */
public class GraphQLObjectMapper implements IGraphQLObjectMapper, TypeResolver {

	private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLObjectMapper.class);
	// class type mappers
	private ImmutableMap<Type, IGraphQLTypeMapper> classTypeMappers;
	private ImmutableList<IGraphQLTypeMapper> interfaceTypeMappers;
	private IGraphQLTypeCache<GraphQLOutputType> outputTypeCache = new DefaultGraphQLTypeCache<>();
	private IGraphQLTypeCache<GraphQLInputType> inputTypeCache = new DefaultGraphQLTypeCache<>();
	private ITypeFactory typeFactory;
	private ITypeNamingStrategy typeNamingStrategy = new SimpleTypeNamingStrategy();
	private List<Class<?>> relayNodeTypes;
	private Stack<Map<String, Type>> typeArguments = new Stack<>();
	private Set<GraphQLType> inputTypes = Sets.newHashSet();
	private String nodeTypeName;
	private IDataFetcherFactory dataFetcherFactory = new DefaultDataFetcherFactory();
	private Class<? extends IDataFetcher> defaultMethodDataFetcher;
	private Map<Class<?>, Class<? extends DefaultTypeConverter>> defaultTypeConverters;

	public GraphQLObjectMapper(ITypeFactory typeFactory, List<IGraphQLTypeMapper> graphQLTypeMappers, Optional<ITypeNamingStrategy> typeNamingStrategy,
			Optional<IDataFetcherFactory> dataFetcherFactory, Optional<Class<? extends IDataFetcher>> defaultMethodDataFetcher,
			Map<Class<?>, Class<? extends DefaultTypeConverter>> typeConverters, List<Class<?>> relayNodeTypes) {

		this.typeFactory = typeFactory;
		this.relayNodeTypes = relayNodeTypes;
		this.defaultTypeConverters = typeConverters;
		this.setDefaultMethodDataFetcher(defaultMethodDataFetcher.or(DefaultMethodDataFetcher.class));

		if (typeNamingStrategy.isPresent()) {
			this.typeNamingStrategy = typeNamingStrategy.get();
		}

		if (dataFetcherFactory.isPresent()) {
			this.setDataFetcherFactory(dataFetcherFactory.get());
		}

		ImmutableList.Builder<IGraphQLTypeMapper> interfaceTypeMappersBuilder = ImmutableList.builder();
		Map<Type, IGraphQLTypeMapper> classTypeMappersBuilder = Maps.newHashMap();

		for (IGraphQLTypeMapper mapper : graphQLTypeMappers) {
			GraphQLTypeMapper mapperAnnotation = mapper.getClass().getAnnotation(GraphQLTypeMapper.class);
			if (mapperAnnotation.type().isInterface()) {
				interfaceTypeMappersBuilder.add(mapper);
			} else {
				classTypeMappersBuilder.put(mapperAnnotation.type(), mapper);
			}
		}
		this.interfaceTypeMappers = interfaceTypeMappersBuilder.build();
		this.classTypeMappers = ImmutableMap.copyOf(classTypeMappersBuilder);

		// store the Node type interface mapping
		nodeTypeName = getTypeNamingStrategy().getTypeName(this, INode.class);
		this.getOutputTypeCache().put(nodeTypeName,
				GraphQLInterfaceType.newInterface()
						.name(nodeTypeName)
						.typeResolver(this)
						.field(GraphQLFieldDefinition.newFieldDefinition().name("id").type(Scalars.GraphQLString).build())
						.build());
	}

	private void buildGenericArgumentTypeMap(ParameterizedType type) {
		Class<?> rawClass = (Class<?>) type.getRawType();
		TypeVariable<?>[] typeVariables = rawClass.getTypeParameters();
		Type[] arguments = type.getActualTypeArguments();
		for (int i = 0; i < typeVariables.length; i++) {
			// field definitions can cause us to come in here so we ignore type variable argument types.
			if (!(arguments[i] instanceof TypeVariable)) {
				typeArguments.peek().put(typeVariables[i].getName(), arguments[i]);
			} else {
				// we might be mapping one variable name to another so do that here
				// IE: MyObject<R,S> {
				// MyInnerObject<S,R>
				// }
				// MyInnerOBject<R,S> {
				// R rType;
				// }
				// we need to update the current with the type from the parents map, so we pop, update, push
				Map<String, Type> current = typeArguments.pop();
				current.put(typeVariables[i].getName(), typeArguments.peek().get(((TypeVariable<?>) arguments[i]).getName()));
				typeArguments.push(current);
			}
		}
	}

	private Optional<String> getFieldNameFromMethod(Method m) {
		Optional<String> fieldName = Optional.absent();

		// Do not match 'get' method
		if (m.getName().startsWith("get") && m.getName().length() > 3) {
			fieldName =  Optional.of(m.getName().substring(3, 4).toLowerCase() + m.getName().substring(4));
		}
		// Do not match 'is' method
		if (m.getName().startsWith("is") && m.getName().length() > 2) {
			fieldName = Optional.of(m.getName().substring(2, 3).toLowerCase() + m.getName().substring(3));
		}
		GraphQLQuery query = m.getAnnotation(GraphQLQuery.class);
		if (query != null) {
			if (AnnotationUtils.isNullValue(query.name())) {
				fieldName = Optional.of(m.getName());
			} else{
				fieldName = Optional.of(query.name());
			}
		}
		GraphQLMutation mutation = m.getAnnotation(GraphQLMutation.class);
		if (mutation != null) {
			if (AnnotationUtils.isNullValue(mutation.name())) {
				fieldName = Optional.of(m.getName());
			} else{
				fieldName = Optional.of(mutation.name());
			}
		}
		return fieldName;
	}

	private Optional<Field> getField(Class<?> typeClass, String fieldName) {
		try {
			return Optional.of(typeClass.getDeclaredField(fieldName));
		} catch (Exception ex) {
			LOGGER.info("No field matching :" + fieldName);
		}
		return Optional.absent();
	}

	private GraphQLFieldDefinition.Builder processDeprecated(GraphQLFieldDefinition.Builder builder, Optional<Method> method, Optional<Field> field) {
		GraphQLDeprecated deprecated = null;
		Deprecated javaDeprecreated = null;
		if (method.isPresent()) {
			deprecated = method.get().getAnnotation(GraphQLDeprecated.class);
			javaDeprecreated = method.get().getAnnotation(Deprecated.class);
		}
		if (field.isPresent()) {
			if (deprecated == null) {
				deprecated = field.get().getAnnotation(GraphQLDeprecated.class);
			}
			if (javaDeprecreated == null) {
				javaDeprecreated = field.get().getAnnotation(Deprecated.class);
			}
		}

		if (deprecated != null) {
			builder.deprecate(deprecated.value());
		} else if (javaDeprecreated != null) {
			builder.deprecate("");
		}

		return builder;
	}
	private Optional<GraphQLDescription> processDescription(Optional<Method> method, Optional<Field> field) {
		Optional<GraphQLDescription> maybeDescription = Optional.absent();
		if (method.isPresent()) {
			maybeDescription = Optional.fromNullable(method.get().getAnnotation(GraphQLDescription.class));
		}
		if (field.isPresent() && !maybeDescription.isPresent()) {
			if (!maybeDescription.isPresent() ) {
				maybeDescription = Optional.fromNullable(field.get().getAnnotation(GraphQLDescription.class));
			}
		}

		return maybeDescription;
	}

	public Optional<GraphQLFieldDefinition> getFieldType(Type type, Method method, Optional<Object> targetObject,  Optional<String> providedFieldName) {
		Optional<String> fieldName = providedFieldName.isPresent() ? providedFieldName : getFieldNameFromMethod(method);
		if (!fieldName.isPresent()) {
			return Optional.absent();
		}

		Type fieldType = method.getGenericReturnType();
		Class<?> fieldTypeClass = getClassFromType(fieldType);
		GraphQLOutputType graphQLFieldType = getOutputType(fieldType);
		Class<? extends DataFetcher> dataFetcherClass = getDefaultMethodDataFetcher();
		Class<?> typeClass = getClassFromType(type);
		Optional<Field> field = getField(typeClass, fieldName.get());


		GraphQLName name = method.getAnnotation(GraphQLName.class);
		if (name != null) {
			fieldName = Optional.of(name.name());
		}

		GraphQLFieldDefinition.Builder builder = GraphQLFieldDefinition.newFieldDefinition().name(fieldName.get()).type(graphQLFieldType);

		// first check if it has a custom type mapper, if so attach the dataFetcher to the field if specified
		Optional<IGraphQLTypeMapper> typeMapper = getCustomTypeMapper(fieldType);
		if (typeMapper.isPresent()) {
			GraphQLTypeMapper typeMapperAnnotation = typeMapper.get().getClass().getAnnotation(GraphQLTypeMapper.class);
			if (!AnnotationUtils.isNullValue(typeMapperAnnotation.dataFetcher())) {
				dataFetcherClass = typeMapperAnnotation.dataFetcher();
			}
		}

		// next check if there is annotation on the method
		GraphQLDataFetcher dataFetcherAnnotation = method.getAnnotation(GraphQLDataFetcher.class);
		// next check field for a datafetcher annotation in case it's not on the method
		if (dataFetcherAnnotation == null) {
			if (field.isPresent()) {
				dataFetcherAnnotation = field.get().getAnnotation(GraphQLDataFetcher.class);
			}
		}

		// explicit annotation for data fetcher?
		if (dataFetcherAnnotation != null) {
			dataFetcherClass = dataFetcherAnnotation.dataFetcher();
		}

		DataFetcher dataFetcher;
		// if we have a datafetcher lets create it using the factory
		dataFetcher = getDataFetcherFactory().newMethodDataFetcher(this, targetObject, method, fieldName.get(), dataFetcherClass);
		if (IDataFetcher.class.isAssignableFrom(dataFetcher.getClass())) {
			processMethodArguments(builder, (IDataFetcher) dataFetcher, method);
		}

		dataFetcher = addTypeConverter(Optional.fromNullable(method.getAnnotation(GraphQLTypeConverter.class)), fieldTypeClass, dataFetcher);

		builder.dataFetcher(dataFetcher);
		processDeprecated(builder, Optional.of(method), field);
		Optional<GraphQLDescription> maybeDescription = processDescription(Optional.of(method), field);
		if (maybeDescription.isPresent()) {
			builder.description(maybeDescription.get().value());
		}

		return Optional.of(builder.build());
	}

	private void processMethodArguments(GraphQLFieldDefinition.Builder builder, IDataFetcher dataFetcher, Method method) {
		Annotation[][] parameterAnnotations = method.getParameterAnnotations();
		int index = 0;
		for (Type paramType : method.getGenericParameterTypes()) {
			Optional<GraphQLParam> maybeGraphQLParam = Optional.fromNullable(AnnotationUtils.findAnnotation(parameterAnnotations[index], GraphQLParam.class));
			Optional<GraphQLDescription> maybeGraphQLParamDesc = Optional.fromNullable(AnnotationUtils.findAnnotation(parameterAnnotations[index], GraphQLDescription.class));

			if (!maybeGraphQLParam.isPresent()) {
				LOGGER.error("Missing @GraphParam annotation on parameter index {} for method {}", index, method.getName());
				continue;
			}
			GraphQLParam graphQLParam = maybeGraphQLParam.get();

			GraphQLInputType inputType = getInputType(paramType);

			if (graphQLParam.required()) {
				inputType = new GraphQLNonNull(inputType);
			}

			GraphQLArgument.Builder argBuilder = GraphQLArgument.newArgument().type(inputType).name(graphQLParam.name());

			if (maybeGraphQLParamDesc.isPresent()) {
				argBuilder.description(maybeGraphQLParamDesc.get().value());
			}

			if (!AnnotationUtils.isNullValue(graphQLParam.defaultValue())) {
				argBuilder.defaultValue(graphQLParam.defaultValue());
			}

			builder.argument(argBuilder.build());

			dataFetcher.addParam(graphQLParam.name(),
					paramType,
					Optional.<Object> fromNullable(AnnotationUtils.isNullValue(graphQLParam.defaultValue()) ? null : graphQLParam.defaultValue()));

			index++;
		}
	}

	private DataFetcher addTypeConverter(Optional<GraphQLTypeConverter> typeConverterAnnotation, Class<?> fieldTypeClass, DataFetcher dataFetcher) {

		Class<? extends DefaultTypeConverter> typeConverterClass= null;
		if (typeConverterAnnotation.isPresent()) {
			typeConverterClass = typeConverterAnnotation.get().typeConverter();
		} else {
			for (Map.Entry<Class<?>, Class<? extends DefaultTypeConverter>> entry : defaultTypeConverters.entrySet()) {

				if (entry.getKey().isAssignableFrom(fieldTypeClass)) {
					typeConverterClass = entry.getValue();
				}
			}
		}
		if (typeConverterClass != null) {
			try {
				dataFetcher = typeConverterClass.getConstructor(DataFetcher.class).newInstance(dataFetcher);
			} catch (Exception ex) {
				Throwables.propagate(ex);
			}
		}
		return dataFetcher;
	}

	public Optional<GraphQLFieldDefinition> getFieldType(Type type, Field field, Optional<Object> targetObject, Optional<String> providedFieldName) {
		String fieldName = providedFieldName.isPresent() ? providedFieldName.get() : field.getName();

		if (Modifier.isAbstract(field.getModifiers())) {
			LOGGER.info("Ignoring types {} abstract field {}  ", type, field);
			return Optional.absent();
		}
		if (Modifier.isStatic(field.getModifiers())) {
			LOGGER.info("Ignoring types {} static field {}  ", type, field);
			return Optional.absent();
		}
		if ("this$0".equals(fieldName)) {
			// this is a dirty hack but we don't want to expose the parent pointer of inner classes...
			return Optional.absent();
		} else {
			LOGGER.info("Processing types {} field {}  ", type, field);
			GraphQLName name = field.getAnnotation(GraphQLName.class);
			Optional<GraphQLDescription> maybeDescription = Optional.fromNullable(field.getAnnotation(GraphQLDescription.class));
			if (name != null) {
				fieldName = name.name();
			}
			try {
				Class<?> fieldTypeClass = getClassFromType(field.getGenericType());
				GraphQLOutputType graphQLFieldType = getOutputType(field.getGenericType());
				GraphQLFieldDefinition.Builder builder = GraphQLFieldDefinition.newFieldDefinition().name(fieldName).type(graphQLFieldType);
				Class<? extends DataFetcher> dataFetcherClass = null;

				// first check if it has a custom type mapper, if so attach the dataFetcher to the field if specified
				Optional<IGraphQLTypeMapper> typeMapper = getCustomTypeMapper(field.getGenericType());
				if (typeMapper.isPresent()) {
					GraphQLTypeMapper typeMapperAnnotation = typeMapper.get().getClass().getAnnotation(GraphQLTypeMapper.class);
					if (!AnnotationUtils.isNullValue(typeMapperAnnotation.dataFetcher())) {
						dataFetcherClass = typeMapperAnnotation.dataFetcher();
					}
				}

				// set the description for the field if provided
				if (maybeDescription.isPresent() && !AnnotationUtils.isNullValue(maybeDescription.get().value())) {
					builder.description(maybeDescription.get().value());
				}

				// if the field type class is not null and we have an annotation on it for a custom datafetcher than use it
				if (field != null) {
					GraphQLDataFetcher dataFetcherAnnotation = field.getAnnotation(GraphQLDataFetcher.class);
					if (dataFetcherAnnotation != null) {
						dataFetcherClass = dataFetcherAnnotation.dataFetcher();
					}
				}

				DataFetcher	dataFetcher = getDataFetcherFactory().newFieldDataFetcher(this, targetObject,  field, fieldName,  dataFetcherClass);
				dataFetcher = addTypeConverter(Optional.fromNullable(field.getAnnotation(GraphQLTypeConverter.class)), fieldTypeClass, dataFetcher);

				if (dataFetcher != null) {
					builder.dataFetcher(dataFetcher);
				}

				processDeprecated(builder, Optional.<Method>absent(), Optional.of(field));

				return Optional.of(builder.build());
			} catch (NotMappableException ex) {
				LOGGER.info("types field type {} not supported so ignored field named {}", type, field.getGenericType(), field.getName());
			}
		}
		return Optional.absent();
	}

	public Optional<GraphQLInputObjectField> getInputFieldType(Type type, Method method, Optional<Object> targetObject,  Optional<String> providedFieldName) {
		Optional<String> fieldName = providedFieldName.isPresent() ? providedFieldName : getFieldNameFromMethod(method);
		if (!fieldName.isPresent()) {
			return Optional.absent();
		}

		Type fieldType = method.getGenericReturnType();
		GraphQLInputType graphQLFieldType = getInputType(fieldType);
		Class<?> typeClass = getClassFromType(type);
		Optional<Field> field = getField(typeClass, fieldName.get());


		GraphQLName name = method.getAnnotation(GraphQLName.class);
		if (name != null) {
			fieldName = Optional.of(name.name());
		}

		GraphQLInputObjectField.Builder builder = new GraphQLInputObjectField.Builder().name(fieldName.get()).type(graphQLFieldType);

		Optional<GraphQLDescription> maybeDescription = processDescription(Optional.of(method), field);
		if (maybeDescription.isPresent()) {
			builder.description(maybeDescription.get().value());
		}

		return Optional.of(builder.build());
	}

	private Optional<GraphQLInputObjectField> getInputFieldType(Type type, Field field, Optional<Object> targetObject, Optional<String> providedFieldName) {
		String fieldName = providedFieldName.isPresent() ? providedFieldName.get() : field.getName();

		if (Modifier.isAbstract(field.getModifiers())) {
			LOGGER.info("Ignoring input abstract field {}#{}  ", type, field);
			return Optional.absent();
		}
		if (Modifier.isStatic(field.getModifiers())) {
			LOGGER.info("Ignoring input static field {}#{}  ", type, field);
			return Optional.absent();
		}
		if ("this$0".equals(fieldName)) {
			// this is a dirty hack but we don't want to expose the parent pointer of inner classes...
			return Optional.absent();
		}
		LOGGER.info("Processing input field {}#{}  ", type, field);
		GraphQLName name = field.getAnnotation(GraphQLName.class);
		Optional<GraphQLDescription> maybeDescription = Optional.fromNullable(field.getAnnotation(GraphQLDescription.class));
		if (name != null) {
			fieldName = name.name();
		}
		GraphQLInputType graphQLFieldType = getInputType(field.getGenericType());
		GraphQLInputObjectField.Builder builder = new GraphQLInputObjectField.Builder().name(fieldName).type(graphQLFieldType);

		// set the description for the field if provided
		if (maybeDescription.isPresent() && !AnnotationUtils.isNullValue(maybeDescription.get().value())) {
			builder.description(maybeDescription.get().value());
		}

		return Optional.of(builder.build());
	}

	private Optional<IGraphQLTypeMapper> getCustomTypeMapper(Type type) {
		if (getClassTypeMappers().containsKey(type)) {
			return Optional.fromNullable(getClassTypeMappers().get(type));
		}
		// type variables can't have custom type mappers
		if (type instanceof TypeVariable) {
			return Optional.absent();
		}
		for (IGraphQLTypeMapper typeMapper : getInterfaceTypeMappers()) {

			if (typeMapper.handlesType(this, type)) {
				return Optional.of(typeMapper);
			}

		}

		return Optional.absent();
	}

	@Override
	public GraphQLInputType getInputType(Type type) {
		String typeName = this.getTypeNamingStrategy().getTypeName(this, type);
		if (getInputTypeCache().containsKey(typeName)) {
			return getInputTypeCache().get(typeName);
		}
		
		Optional<IGraphQLTypeMapper> typeMapper = getCustomTypeMapper(type);
		if (typeMapper.isPresent()) {
			return getInputTypeCache().put(typeName, typeMapper.get().getInputType(this, type));
		}
		if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			Type rawType = parameterizedType.getRawType();
			Class<?> rawClass = (Class<?>) rawType;

			typeMapper = getCustomTypeMapper(rawClass);
			if (typeMapper.isPresent()) {
				return getInputTypeCache().put(typeName, typeMapper.get().getInputType(this, type));
			} else {
				return buildInputObject(type, rawClass);
			}
		}
		if (type instanceof TypeVariable) {
			TypeVariable<?> vType = (TypeVariable<?>) type;
			return getInputType(typeArguments.peek().get(vType.getName()));
		} else {
			Class<?> classType = getClassFromType(type);
			Optional<GraphQLScalarType> graphQLType = getIfPrimitiveType(classType);

			if (graphQLType.isPresent()) {
				return getInputTypeCache().put(typeName, graphQLType.get());
			}
			if (classType.isEnum()) {
				GraphQLEnumType.Builder enumType = GraphQLEnumType.newEnum().name(typeNamingStrategy.getTypeName(this, type));
				for (Object value : classType.getEnumConstants()) {
					enumType.value(value.toString(), value);
				}
				return getInputTypeCache().put(typeName, enumType.build());
			}
			return buildInputObject(type, classType);
		}
	}

	@Override
	public GraphQLOutputType getOutputType(Type type) {
		GraphQLOutputType rv;
		Class<?> classType;
		String typeName = this.getTypeNamingStrategy().getTypeName(this, type);

		if (getOutputTypeCache().containsKey(typeName)) {
			return getOutputTypeCache().get(typeName);
		}

		Optional<IGraphQLTypeMapper> typeMapper = getCustomTypeMapper(type);
		if (typeMapper.isPresent()) {
			rv = getOutputTypeCache().put(typeName, typeMapper.get().getOutputType(this, type));
		} else if (type instanceof ParameterizedType) {
			ParameterizedType parameterizedType = (ParameterizedType) type;
			Type rawType = parameterizedType.getRawType();
			Class<?> rawClass = (Class<?>) rawType;

			typeMapper = getCustomTypeMapper(rawClass);
			if (typeMapper.isPresent()) {
				rv = getOutputTypeCache().put(typeName, typeMapper.get().getOutputType(this, type));
			} else {
				return buildObject(type, rawClass);
			}
		} else if (type instanceof TypeVariable) {
			TypeVariable<?> vType = (TypeVariable<?>) type;
			return getOutputType(typeArguments.peek().get(vType.getName()));
		} else {
			classType = getClassFromType(type);
			Optional<GraphQLScalarType> graphQLType = getIfPrimitiveType(classType);

			if (graphQLType.isPresent()) {
				rv = getOutputTypeCache().put(typeName, graphQLType.get());
			} else if (classType.isEnum()) {
				GraphQLEnumType.Builder enumType = GraphQLEnumType.newEnum().name(typeNamingStrategy.getTypeName(this, type));
				for (Object value : classType.getEnumConstants()) {
					enumType.value(value.toString(), value);
				}
				rv = getOutputTypeCache().put(typeName, enumType.build());
			} else {
				rv = buildObject(type, classType);
			}
		}

		return rv;
	}

	@Override
	public IGraphQLTypeCache<GraphQLInputType> getInputTypeCache() {
		return inputTypeCache;
	}

	@Override
	public IGraphQLTypeCache<GraphQLOutputType> getOutputTypeCache() {
		return outputTypeCache;
	}

	@Override
	public ITypeFactory getTypeFactory() {
		return this.typeFactory;
	}

	private Optional<GraphQLScalarType> getIfPrimitiveType(Class<?> classType) {
		GraphQLScalarType rv = null;
		// native types
		if (Integer.class.isAssignableFrom(classType) || classType.isAssignableFrom(int.class)) {
			rv = Scalars.GraphQLInt;
		} else if (Long.class.isAssignableFrom(classType) || long.class.isAssignableFrom(classType)) {
			rv = Scalars.GraphQLLong;
		} else if (Float.class.isAssignableFrom(classType) || float.class.isAssignableFrom(classType)) {
			rv = Scalars.GraphQLFloat;
		} else if (Double.class.isAssignableFrom(classType) || double.class.isAssignableFrom(classType)) {
			rv = Scalars.GraphQLFloat;
		} else if (String.class.isAssignableFrom(classType)) {
			rv = Scalars.GraphQLString;
		} else if (Boolean.class.isAssignableFrom(classType) || boolean.class.isAssignableFrom(classType)) {
			rv = Scalars.GraphQLBoolean;
		}

		return Optional.fromNullable(rv);
	}

	private GraphQLInputObjectType buildInputObject(Type type, Class<?> classType) {
		String inputTypeName = String.format("%s%s%s", this.getTypeNamingStrategy().getTypeName(this, type),
				this.getTypeNamingStrategy().getDelimiter(), this.getTypeNamingStrategy().getInputTypePostfix());

		GraphQLInputObjectType.Builder glType = new GraphQLInputObjectType.Builder().name(inputTypeName);
		ImmutableList.Builder<GraphQLInputObjectField> fields = ImmutableList.builder();

		Class<?> classItem = classType;
		Optional<GraphQLDescription> maybeGraphqlDesc = Optional
				.fromNullable(classItem.getAnnotation(GraphQLDescription.class));
		Set<String> fieldNames = Sets.newHashSet();

		if (maybeGraphqlDesc.isPresent()) {
			glType.description(maybeGraphqlDesc.get().value());
		}
		// if we are a generic object then we need to build a generic variable
		// to type mapping
		if (type instanceof ParameterizedType) {
			// if it's empty then we are at root generic class so create new
			// type argument map for usage
			if (typeArguments.empty()) {
				typeArguments.push(Maps.<String, Type>newHashMap());
			} else {
				// we are inside the context of another generic object, so
				// create a copy of parent map to use within new context
				typeArguments.push(Maps.newHashMap(typeArguments.peek()));
			}
			buildGenericArgumentTypeMap((ParameterizedType) type);
		}
		String classPackage = "";

		if (classItem != null && classItem.getPackage() != null) {
			classPackage = classItem.getPackage().getName();
		}
		// end when there are no more super classes and while ignore java.*
		// types
		while (classItem != null && !classPackage.startsWith("java.")) {
			Collection<GraphQLInputObjectField> newInputFields = getGraphQLInputObjectFields(Optional.absent(), type,
					classItem, Optional.<List<Field>>absent(), Optional.<List<Method>>absent());
			if (newInputFields != null) {
				for (GraphQLInputObjectField f : newInputFields) {
					if (!fieldNames.contains(f.getName())) {
						fieldNames.add(f.getName());
						fields.add(f);
					} else {
						LOGGER.info("Ignoring duplicate input field {} from type {}", f.getName(), classItem.getName());
					}
				}
			}

			// pop currentContext
			classItem = classItem.getSuperclass();
			if (classItem != null && classItem.getPackage() != null) {
				classPackage = classItem.getPackage().getName();
			} else {
				classPackage = "";
			}
		}

		// exiting context of current type arguments if we processed a generic
		// type
		if (type instanceof ParameterizedType) {
			typeArguments.pop();
		}
		glType.fields(fields.build());

		return (GraphQLInputObjectType) getInputTypeCache().put(inputTypeName, glType.build());
	}

	private GraphQLObjectType buildObject(Type type, Class<?> classType) {
		GraphQLObjectType rv;
		try {
			// object types we create an object type and then recursively call ourselves to get the field types
			String typeName = typeNamingStrategy.getTypeName(this, type);
			GraphQLObjectType.Builder glType = GraphQLObjectType.newObject().name(typeName);
			GraphQLTypeReference glTypeReference = new GraphQLTypeReference(typeName);
			ImmutableList.Builder<GraphQLFieldDefinition> fields = ImmutableList.builder();

			getOutputTypeCache().put(typeName, glTypeReference);
			Class<?> classItem = classType;
			Optional<GraphQLController> graphQLQueryable = Optional.fromNullable((GraphQLController) classItem.getAnnotation(GraphQLController.class));
			Optional<GraphQLDescription> maybeGraphqlDesc = Optional.fromNullable(classItem.getAnnotation(GraphQLDescription.class));
			Optional<IQueryFactory> queryFactory = Optional.absent();
			Object objectInstance = null;
			Set<String> fieldNames = Sets.newHashSet();

			if (maybeGraphqlDesc.isPresent()) {
				glType.description(maybeGraphqlDesc.get().value());
			}
			// if it's queryable create a factory and instance of the object that we will execute queries upon
			if (graphQLQueryable.isPresent()) {
				objectInstance = classItem.newInstance();
			}
			// if we are a generic object then we need to build a generic variable to type mapping
			if (type instanceof ParameterizedType) {
				// if it's empty then we are at root generic class so create new type argument map for usage
				if (typeArguments.empty()) {
					typeArguments.push(Maps.<String, Type> newHashMap());
				} else {
					// we are inside the context of another generic object, so create a copy of parent map to use within new context
					typeArguments.push(Maps.newHashMap(typeArguments.peek()));
				}
				buildGenericArgumentTypeMap((ParameterizedType) type);
			}
			String classPackage = "";

			if (classItem != null && classItem.getPackage() != null) {
				classPackage = classItem.getPackage().getName();
			}
			// end when there are no more super classes and while ignore java.* types
			while (classItem != null && !classPackage.startsWith("java.")) {
				Collection<GraphQLFieldDefinition> newFields;
				if (queryFactory.isPresent()) {
					newFields = queryFactory.get().newMethodQueriesForObject(this, objectInstance);
				} else {
					newFields = getGraphQLFieldDefinitions(Optional.absent(), type, classItem, Optional.<List<Field>>absent(), Optional.<List<Method>>absent());
				}
				if (newFields != null) {
					for (GraphQLFieldDefinition f : newFields) {
						if (!fieldNames.contains(f.getName())) {
							fieldNames.add(f.getName());
							fields.add(f);
						} else {
							LOGGER.info("Ignoring duplicate field {} from type {}", f.getName(), classItem.getName());
						}
					}
				}

				// pop currentContext
				classItem = classItem.getSuperclass();
				if (classItem != null && classItem.getPackage() != null) {
					classPackage = classItem.getPackage().getName();
				} else {
					classPackage = "";
				}
			}

			// exiting context of current type arguments if we processed a generic type
			if (type instanceof ParameterizedType) {
				typeArguments.pop();
			}
			glType.fields(fields.build());

			// for classes that implement Node we need to declare them of type interface
			if (INode.class.isAssignableFrom(classType)) {
				glType.withInterface((GraphQLInterfaceType) getOutputTypeCache().get(nodeTypeName));
			}
			rv = (GraphQLObjectType) getOutputTypeCache().put(typeName, glType.build());
		} catch (InstantiationException | IllegalAccessException ex) {
			LOGGER.error("Unable to instantiate query factory for type class {}", classType.getName(), ex);
			Throwables.propagate(ex);
			rv = null; // never executed but gets rid of not initialized warning for rv;
		}

		return rv;
	}

	public Collection<GraphQLInputObjectField> getGraphQLInputObjectFields(Optional<Object> targetObject, Type type, Class<?> classItem, Optional<List<Field>> fields, Optional<List<Method>> methods) {
		Map<String, GraphQLInputObjectField> inputFields = Maps.newLinkedHashMap();
		Optional<GraphQLInputObjectField> optionalInputField;
		Set<String> ignoredFields = Sets.newHashSet();
		List<Field> fieldList;
		List<Method> methodList;

		fieldList = fields.or(ImmutableList.copyOf(classItem.getDeclaredFields()));
		methodList = methods.or(ImmutableList.copyOf(classItem.getDeclaredMethods()));

		for (Method m : methodList) {
			Optional<String> methodName = getFieldNameFromMethod(m);
			// we only look at getters and is types
			if (!methodName.isPresent()) {
				continue;
			}
			if (null != m.getAnnotation(GraphQLIgnore.class)) {
				ignoredFields.add(methodName.get());
				continue;
			}
			optionalInputField = getInputFieldType(type, m, targetObject, methodName);
			if (optionalInputField.isPresent()) {
				inputFields.put(optionalInputField.get().getName(), optionalInputField.get());
			}
		}

		for (Field field : fieldList) {
			// skip ignored fields
			if (null != field.getAnnotation(GraphQLIgnore.class) || ignoredFields.contains(field.getName())) {
				// if it's marked ignored then remove it.
				if (inputFields.containsKey(field.getName())) {
					inputFields.remove(field.getName());
				}
				continue;
			}
			if (inputFields.containsKey(field.getName())) {
				continue;
			}
			optionalInputField = getInputFieldType(type, field, targetObject, Optional.<String>absent());
			if (optionalInputField.isPresent()) {
				if (!field.getName().startsWith("$")) {
					inputFields.put(optionalInputField.get().getName(), optionalInputField.get());
				}
			}
		}

		return inputFields.values();
	}
	
	@Override
	public Collection<GraphQLFieldDefinition> getGraphQLFieldDefinitions(Optional<Object> targetObject, Type type, Class<?> classItem, Optional<List<Field>> fields, Optional<List<Method>> methods) {
		Map<String, GraphQLFieldDefinition> fieldDefinitions = Maps.newLinkedHashMap();
		Optional<GraphQLFieldDefinition> fieldDefinitionOptional;
		Set<String> ignoredFields = Sets.newHashSet();
		List<Field> fieldList;
		List<Method> methodList;

		fieldList = fields.or(ImmutableList.copyOf(classItem.getDeclaredFields()));
		methodList = methods.or(ImmutableList.copyOf(classItem.getDeclaredMethods()));

		for (Method m : methodList) {
			Optional<String> methodName = getFieldNameFromMethod(m);
			// we only look at getters and is types
			if (!methodName.isPresent()) {
				continue;
			}
			if (null != m.getAnnotation(GraphQLIgnore.class)) {
				ignoredFields.add(methodName.get());
				continue;
			}
			fieldDefinitionOptional = getFieldType(type, m, targetObject, methodName);
			if (fieldDefinitionOptional.isPresent()) {
				fieldDefinitions.put(fieldDefinitionOptional.get().getName(), fieldDefinitionOptional.get());
			}
		}

		for (Field field : fieldList) {
			// skip ignored fields
			if (null != field.getAnnotation(GraphQLIgnore.class) || ignoredFields.contains(field.getName())) {
				// if it's marked ignored then remove it.
				if (fieldDefinitions.containsKey(field.getName())) {
					fieldDefinitions.remove(field.getName());
				}
				continue;
			}
			if (fieldDefinitions.containsKey(field.getName())) {
				continue;
			}
			fieldDefinitionOptional = getFieldType(type, field, targetObject, Optional.<String>absent());
			if (fieldDefinitionOptional.isPresent()) {
				if (!field.getName().startsWith("$")) {
					fieldDefinitions.put(fieldDefinitionOptional.get().getName(), fieldDefinitionOptional.get());
				}
			}
		}

		return fieldDefinitions.values();
	}

	@Override
	public ITypeNamingStrategy getTypeNamingStrategy() {
		return typeNamingStrategy;
	}

	public void setTypeNamingStrategy(ITypeNamingStrategy typeNamingStrategy) {
		this.typeNamingStrategy = typeNamingStrategy;
	}

	@VisibleForTesting
	ImmutableMap<Type, IGraphQLTypeMapper> getClassTypeMappers() {
		return classTypeMappers;
	}

	@VisibleForTesting
	ImmutableList<IGraphQLTypeMapper> getInterfaceTypeMappers() {
		return interfaceTypeMappers;
	}

	public List<Class<?>> getRelayNodeTypes() {
		return relayNodeTypes;
	}

	@Override
	public GraphQLObjectType getType(Object object) {
		return (GraphQLObjectType) getOutputType(object.getClass());
	}

	@Override
	public Class<?> getClassFromType(Type type) {

		if (type instanceof ParameterizedType) {
			return (Class<?>) ((ParameterizedType) type).getRawType();
		} else if (type instanceof TypeVariable) {
			return getClassFromType(typeArguments.peek().get(((TypeVariable<?>) type).getName()));
		} else if (type instanceof WildcardType) {
			// @TODO do a better job of wild card types here
			return getClassFromType(((WildcardType) type).getLowerBounds()[0]);
		} else {
			return (Class<?>) type;
		}
	}

	@Override
	public Set<GraphQLType> getInputTypes() {
		return inputTypes;
	}

	@Override
	public IDataFetcherFactory getDataFetcherFactory() {
		return dataFetcherFactory;
	}

	@Override
	public void setDataFetcherFactory(IDataFetcherFactory dataFetcherFactory) {
		this.dataFetcherFactory = dataFetcherFactory;
	}

	@Override
	public Class<? extends IDataFetcher> getDefaultMethodDataFetcher() {
		return defaultMethodDataFetcher;
	}

	@Override
	public void setDefaultMethodDataFetcher(Class<? extends IDataFetcher> defaultMethodDataFetcher) {
		this.defaultMethodDataFetcher = defaultMethodDataFetcher;
	}
}
