package com.agent.agentcorelab.tool.annotation;

import com.agent.agentcorelab.tool.ActiveTimeExceededException;
import com.agent.agentcorelab.tool.RiskLevel;
import com.agent.agentcorelab.tool.Tool;
import com.agent.agentcorelab.tool.ToolContext;
import com.agent.agentcorelab.tool.ToolDefinition;
import com.agent.agentcorelab.tool.ToolJson;
import com.agent.agentcorelab.tool.ToolOutcome;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter that turns an {@link AgentTool} annotated Java method into an executable {@link Tool}.
 */
public final class AnnotatedTool implements Tool {

    private final Object target;
    private final Method method;
    private final ToolDefinition definition;
    private final List<ParamDescriptor> descriptors;

    public AnnotatedTool(Object target, Method method) {
        this.target = Objects.requireNonNull(target, "target");
        this.method = Objects.requireNonNull(method, "method");
        this.method.setAccessible(true);

        AgentTool annotation = method.getAnnotation(AgentTool.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Method must be annotated with @AgentTool: " + method.getName());
        }

        String name = annotation.name().isBlank() ? method.getName() : annotation.name().trim();
        String description = annotation.description();
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description cannot be blank for method: " + method.getName());
        }

        String inputSchema = JsonSchemaGenerator.generateSchema(method);
        RiskLevel riskLevel = annotation.riskLevel() != null ? annotation.riskLevel() : RiskLevel.READ_ONLY;
        this.definition = new ToolDefinition(name, description, inputSchema, riskLevel);

        List<ParamDescriptor> params = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            Parameter p = parameters[i];
            if (JsonSchemaGenerator.isContextParameter(p)) {
                params.add(new ParamDescriptor(i, null, p.getType(), p.getParameterizedType(), true, false, null, false));
            } else {
                String paramName = JsonSchemaGenerator.resolveParameterName(p);
                if (!seenNames.add(paramName)) {
                    throw new IllegalArgumentException("Duplicate parameter name '" + paramName + "' in method: " + method.getName());
                }
                ToolParam toolParam = p.getAnnotation(ToolParam.class);
                boolean required = JsonSchemaGenerator.isRequiredParameter(toolParam);
                String defValue = (toolParam != null && !toolParam.defaultValue().isEmpty()) ? toolParam.defaultValue() : null;
                boolean isRecordRoot = JsonSchemaGenerator.isFlattenedRecordParameter(method, p);
                params.add(new ParamDescriptor(i, paramName, p.getType(), p.getParameterizedType(), false, required, defValue, isRecordRoot));
            }
        }
        this.descriptors = List.copyOf(params);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolOutcome execute(String argumentsJson, ToolContext context) {
        context.checkActive();
        JsonNode argsNode;
        try {
            argsNode = ToolJson.parse(argumentsJson);
        } catch (IllegalArgumentException e) {
            return new ToolOutcome.Failure("INVALID_ARGUMENT", "Invalid arguments JSON: " + e.getMessage(), false);
        }

        Object[] invokeArgs = new Object[descriptors.size()];
        for (ParamDescriptor desc : descriptors) {
            if (desc.isContext()) {
                invokeArgs[desc.index()] = context;
                continue;
            }

            JsonNode valueNode = argsNode.get(desc.name());
            if (valueNode == null || valueNode.isNull()) {
                if (desc.isRecordRoot()) {
                    try {
                        Object argVal = convertFromNode(argsNode, desc.type(), desc.genericType());
                        validateIfPresent(argVal);
                        invokeArgs[desc.index()] = argVal;
                    } catch (IllegalArgumentException e) {
                        return new ToolOutcome.Failure("INVALID_ARGUMENT", e.getMessage(), false);
                    } catch (Exception e) {
                        return new ToolOutcome.Failure("INVALID_ARGUMENT",
                                "Invalid arguments for parameter " + desc.name() + ": " + e.getMessage(), false);
                    }
                } else if (desc.defaultValue() != null) {
                    try {
                        Object argVal = convertFromString(desc.defaultValue(), desc.type(), desc.genericType());
                        validateIfPresent(argVal);
                        invokeArgs[desc.index()] = argVal;
                    } catch (IllegalArgumentException e) {
                        return new ToolOutcome.Failure("INVALID_ARGUMENT", e.getMessage(), false);
                    } catch (Exception e) {
                        return new ToolOutcome.Failure("INVALID_ARGUMENT",
                                "Failed to parse default value for parameter " + desc.name() + ": " + e.getMessage(), false);
                    }
                } else if (desc.required()) {
                    return new ToolOutcome.Failure("INVALID_ARGUMENT", "Missing required parameter: " + desc.name(), false);
                } else {
                    invokeArgs[desc.index()] = defaultPrimitiveValue(desc.type());
                }
            } else {
                try {
                    Object argVal = convertFromNode(valueNode, desc.type(), desc.genericType());
                    validateIfPresent(argVal);
                    invokeArgs[desc.index()] = argVal;
                } catch (IllegalArgumentException e) {
                    return new ToolOutcome.Failure("INVALID_ARGUMENT", e.getMessage(), false);
                } catch (Exception e) {
                    return new ToolOutcome.Failure("INVALID_ARGUMENT",
                            "Invalid argument for parameter " + desc.name() + ": " + e.getMessage(), false);
                }
            }
        }

        try {
            Object result = method.invoke(target, invokeArgs);
            return formatOutcome(result);
        } catch (InvocationTargetException e) {
            Throwable targetEx = e.getTargetException();
            if (targetEx instanceof ActiveTimeExceededException ate) {
                throw ate;
            }
            String message = targetEx.getMessage();
            if (message == null || message.isBlank()) {
                message = targetEx.getClass().getSimpleName();
            }
            return new ToolOutcome.Failure("EXECUTION_ERROR", message, false);
        } catch (Exception e) {
            return new ToolOutcome.Failure("EXECUTION_ERROR", e.getMessage(), false);
        }
    }

    private Object convertFromNode(JsonNode node, Class<?> rawType, Type genericType) {
        if (rawType.isEnum() && node.isTextual()) {
            String text = node.asText().trim();
            for (Object constant : rawType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(text)) {
                    return constant;
                }
            }
        }
        JavaType javaType = ToolJson.mapper().constructType(genericType);
        return ToolJson.mapper().convertValue(node, javaType);
    }

    private Object convertFromString(String text, Class<?> rawType, Type genericType) throws Exception {
        if (String.class.equals(rawType) || CharSequence.class.equals(rawType)) {
            return text;
        }
        if (int.class.equals(rawType) || Integer.class.equals(rawType)) {
            return Integer.parseInt(text.trim());
        }
        if (long.class.equals(rawType) || Long.class.equals(rawType)) {
            return Long.parseLong(text.trim());
        }
        if (double.class.equals(rawType) || Double.class.equals(rawType)) {
            return Double.parseDouble(text.trim());
        }
        if (float.class.equals(rawType) || Float.class.equals(rawType)) {
            return Float.parseFloat(text.trim());
        }
        if (boolean.class.equals(rawType) || Boolean.class.equals(rawType)) {
            return Boolean.parseBoolean(text.trim());
        }
        if (rawType.isEnum()) {
            for (Object constant : rawType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(text.trim())) {
                    return constant;
                }
            }
        }
        JavaType javaType = ToolJson.mapper().constructType(genericType);
        return ToolJson.mapper().readValue(text, javaType);
    }

    private Object defaultPrimitiveValue(Class<?> type) {
        if (boolean.class.equals(type)) return false;
        if (byte.class.equals(type)) return (byte) 0;
        if (short.class.equals(type)) return (short) 0;
        if (int.class.equals(type)) return 0;
        if (long.class.equals(type)) return 0L;
        if (float.class.equals(type)) return 0.0f;
        if (double.class.equals(type)) return 0.0d;
        if (char.class.equals(type)) return '\0';
        return null;
    }

    private ToolOutcome formatOutcome(Object result) {
        if (result instanceof ToolOutcome outcome) {
            return outcome;
        }
        if (result == null) {
            return new ToolOutcome.Success("{\"ok\":true}");
        }
        if (result instanceof String s) {
            String trimmed = s.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    ToolJson.parse(trimmed);
                    return new ToolOutcome.Success(trimmed);
                } catch (Exception ignore) {
                    // fall through to write as object
                }
            }
            return new ToolOutcome.Success(ToolJson.write(Map.of("result", s)));
        }
        return new ToolOutcome.Success(ToolJson.write(result));
    }

    private static void validateIfPresent(Object target) {
        if (target == null || ValidatorHolder.VALIDATOR == null || ValidatorHolder.VALIDATE_METHOD == null) {
            return;
        }
        try {
            Set<?> violations = (Set<?>) ValidatorHolder.VALIDATE_METHOD.invoke(
                    ValidatorHolder.VALIDATOR, target, new Class<?>[0]);
            if (violations != null && !violations.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object v : violations) {
                    Method getPropPath = v.getClass().getMethod("getPropertyPath");
                    Method getMessage = v.getClass().getMethod("getMessage");
                    if (!sb.isEmpty()) {
                        sb.append("; ");
                    }
                    sb.append(getPropPath.invoke(v)).append(": ").append(getMessage.invoke(v));
                }
                throw new IllegalArgumentException("参数校验失败: " + sb);
            }
        } catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e.getTargetException());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignore) {
        }
    }

    private static final class ValidatorHolder {
        private static final Object VALIDATOR;
        private static final Method VALIDATE_METHOD;

        static {
            Object v = null;
            Method m = null;
            try {
                Class<?> factoryClass = Class.forName("jakarta.validation.Validation");
                Method buildDefault = factoryClass.getMethod("buildDefaultValidatorFactory");
                Object factory = buildDefault.invoke(null);
                Method getValidator = factory.getClass().getMethod("getValidator");
                v = getValidator.invoke(factory);
                m = v.getClass().getMethod("validate", Object.class, Class[].class);
            } catch (Throwable ignored) {
                // Jakarta Validation not on classpath or failed to init
            }
            VALIDATOR = v;
            VALIDATE_METHOD = m;
        }
    }

    private record ParamDescriptor(
            int index,
            String name,
            Class<?> type,
            Type genericType,
            boolean isContext,
            boolean required,
            String defaultValue,
            boolean isRecordRoot
    ) {}
}
