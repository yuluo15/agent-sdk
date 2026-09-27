package com.agent.agentcorelab.tool.annotation;

import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Generates JSON Schema (Draft 2020-12 compatible) from Java methods annotated with {@link AgentTool}.
 */
public final class JsonSchemaGenerator {

    private JsonSchemaGenerator() {
    }

    /**
     * Generates a canonical JSON Schema string for the given method parameters.
     */
    public static String generateSchema(Method method) {
        Objects.requireNonNull(method, "method");
        ObjectNode root = ToolJson.mapper().createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = root.putObject("properties");
        ArrayNode requiredNode = root.putArray("required");

        for (Parameter parameter : method.getParameters()) {
            if (isContextParameter(parameter)) {
                continue;
            }

            String paramName = resolveParameterName(parameter);
            ToolParam toolParam = parameter.getAnnotation(ToolParam.class);

            ObjectNode propertyNode = propertiesNode.putObject(paramName);
            buildTypeSchema(propertyNode, parameter.getType(), parameter.getParameterizedType());

            if (toolParam != null && !toolParam.description().isBlank()) {
                propertyNode.put("description", toolParam.description().trim());
            }

            boolean isRequired = isRequiredParameter(toolParam);
            if (isRequired) {
                requiredNode.add(paramName);
            }
        }

        if (requiredNode.isEmpty()) {
            root.remove("required");
        }

        root.put("additionalProperties", false);
        return ToolJson.canonical(root);
    }

    public static boolean isContextParameter(Parameter parameter) {
        return ToolContext.class.isAssignableFrom(parameter.getType());
    }

    public static String resolveParameterName(Parameter parameter) {
        ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
        if (toolParam != null && !toolParam.name().isBlank()) {
            String name = toolParam.name().trim();
            validateName(name);
            return name;
        }
        String paramName = parameter.getName();
        validateName(paramName);
        return paramName;
    }

    public static boolean isRequiredParameter(ToolParam toolParam) {
        if (toolParam == null) {
            return true;
        }
        if (!toolParam.defaultValue().isEmpty()) {
            return false;
        }
        return toolParam.required();
    }

    private static void validateName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Parameter name must match [a-zA-Z0-9_-]{1,64}: " + name);
        }
    }

    private static void buildTypeSchema(ObjectNode target, Class<?> rawType, Type genericType) {
        if (String.class.isAssignableFrom(rawType) || CharSequence.class.isAssignableFrom(rawType)
                || UUID.class.isAssignableFrom(rawType) || char.class.equals(rawType) || Character.class.equals(rawType)) {
            target.put("type", "string");
        } else if (int.class.equals(rawType) || Integer.class.equals(rawType)
                || long.class.equals(rawType) || Long.class.equals(rawType)
                || short.class.equals(rawType) || Short.class.equals(rawType)
                || byte.class.equals(rawType) || Byte.class.equals(rawType)
                || BigInteger.class.isAssignableFrom(rawType)) {
            target.put("type", "integer");
        } else if (double.class.equals(rawType) || Double.class.equals(rawType)
                || float.class.equals(rawType) || Float.class.equals(rawType)
                || BigDecimal.class.isAssignableFrom(rawType)) {
            target.put("type", "number");
        } else if (boolean.class.equals(rawType) || Boolean.class.equals(rawType)) {
            target.put("type", "boolean");
        } else if (rawType.isEnum()) {
            target.put("type", "string");
            ArrayNode enumArray = target.putArray("enum");
            for (Object constant : rawType.getEnumConstants()) {
                enumArray.add(((Enum<?>) constant).name());
            }
        } else if (rawType.isArray()) {
            target.put("type", "array");
            ObjectNode items = target.putObject("items");
            buildTypeSchema(items, rawType.getComponentType(), rawType.getComponentType());
        } else if (Collection.class.isAssignableFrom(rawType)) {
            target.put("type", "array");
            ObjectNode items = target.putObject("items");
            Class<?> itemType = extractGenericType(genericType);
            if (itemType != null) {
                buildTypeSchema(items, itemType, itemType);
            } else {
                items.put("type", "string");
            }
        } else {
            // POJO, Record, Map or other structured object
            target.put("type", "object");
        }
    }

    private static Class<?> extractGenericType(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> clazz) {
                return clazz;
            }
        }
        return null;
    }
}
