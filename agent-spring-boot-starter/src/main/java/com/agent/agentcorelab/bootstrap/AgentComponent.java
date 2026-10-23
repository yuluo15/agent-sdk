package com.agent.agentcorelab.bootstrap;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stereotype annotation declaring a Spring Bean as an Agent Component containing
 * {@link com.agent.agentcorelab.tool.annotation.AgentTool} annotated methods.
 * <p>
 * Classes annotated with {@code @AgentComponent} will be automatically discovered by Spring's component scanning.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface AgentComponent {

    /**
     * The value may indicate a suggestion for a logical component name,
     * to be turned into a Spring bean in case of an autodetected component.
     */
    String value() default "";
}
