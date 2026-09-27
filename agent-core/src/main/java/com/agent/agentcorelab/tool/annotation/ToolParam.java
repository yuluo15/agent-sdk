package com.agent.agentcorelab.tool.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides schema metadata for a parameter of an {@link AgentTool} method.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolParam {

    /**
     * Parameter name exposed in the tool's JSON Schema.
     * If left blank, falls back to the Java method parameter name.
     */
    String name() default "";

    /**
     * Description of this parameter to assist the LLM in understanding expected inputs.
     */
    String description() default "";

    /**
     * Whether this parameter is strictly required in the tool invocation.
     * Defaults to true. If a non-empty {@link #defaultValue()} is provided,
     * the parameter is treated as optional unless required is explicitly specified.
     */
    boolean required() default true;

    /**
     * Default value representation (as a string) when the LLM omits this parameter.
     */
    String defaultValue() default "";
}
