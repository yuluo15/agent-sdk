package com.agent.agentcorelab.model;

import com.agent.agentcorelab.bootstrap.AgentProperties;
import com.agent.agentcorelab.bootstrap.ModelConfiguration;
import com.agent.agentcorelab.bootstrap.ModelProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ModelConfiguration.class));

    @Test
    void disabledByDefaultStartsWithoutAnyCredentials() {
        context.run(result -> {
            assertNull(result.getStartupFailure());
            assertFalse(result.getBean(ModelProperties.class).isEnabled());
            assertNull(result.getBean(ModelProperties.class).getApiKey());
            assertEquals(4096, result.getBean(AgentProperties.class).limits().outputTokenReserve());
            assertTrue(result.getBeansOfType(ModelGateway.class).isEmpty());
        });
    }

    @Test
    void enabledWithoutCredentialsFailsAtStartup() {
        context.withPropertyValues("agent.model.enabled=true", "agent.model.model=local-test-model")
                .run(result -> assertNotNull(result.getStartupFailure()));
    }

    @Test
    void readsEnvironmentWithoutModifyingItOrPrintingKey() {
        Map<String, Object> environment = Map.of(
                "AGENT_MODEL_ENABLED", "true",
                "AGENT_MODEL_APIKEY", "local-test-key",
                "AGENT_MODEL_BASEURL", "http://127.0.0.1:12345/v1",
                "AGENT_MODEL_MODEL", "local-test-model",
                "AGENT_MODEL_MAXOUTPUTTOKENS", "512");
        context.withInitializer(application -> application.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)))
                .run(result -> {
                    assertNull(result.getStartupFailure());
                    assertInstanceOf(SpringAiModelGateway.class, result.getBean(ModelGateway.class));
                    var properties = result.getBean(ModelProperties.class);
                    assertEquals("local-test-key", properties.getApiKey());
                    assertEquals(512, properties.getMaxOutputTokens());
                    assertEquals("http://127.0.0.1:12345/v1", properties.getBaseUrl());
                    assertFalse(properties.toString().contains("local-test-key"));
                    assertEquals("true", environment.get("AGENT_MODEL_ENABLED"));
                });
    }

    @Test
    void enabledOutputLimitCannotExceedRunnerReserve() {
        context.withPropertyValues("agent.model.enabled=true", "agent.model.api-key=local-reserve-key",
                        "agent.model.model=local-test-model", "agent.model.max-output-tokens=513",
                        "agent.limits.output-token-reserve=512")
                .run(result -> {
                    Throwable failure = result.getStartupFailure();
                    assertNotNull(failure);
                    while (failure.getCause() != null) {
                        failure = failure.getCause();
                    }
                    assertInstanceOf(IllegalArgumentException.class, failure);
                    assertEquals("agent.model.maxOutputTokens must not exceed agent.limits.outputTokenReserve",
                            failure.getMessage());
                    assertFalse(failure.toString().contains("local-reserve-key"));
                });
    }

    @Test
    void enabledOutputLimitMayEqualRunnerReserve() {
        context.withPropertyValues("agent.model.enabled=true", "agent.model.api-key=local-test-key",
                        "agent.model.model=local-test-model", "agent.model.max-output-tokens=512",
                        "agent.limits.output-token-reserve=512")
                .run(result -> {
                    assertNull(result.getStartupFailure());
                    assertInstanceOf(SpringAiModelGateway.class, result.getBean(ModelGateway.class));
                });
    }

    @Test
    void disabledModelDoesNotRequireItsOutputLimitToFitReserve() {
        context.withPropertyValues("agent.limits.output-token-reserve=512")
                .run(result -> assertNull(result.getStartupFailure()));
    }

    @Test
    void springAiPropertiesAreNotAliasesForAgentModelProperties() {
        context.withPropertyValues("spring.ai.openai.api-key=ignored-key",
                        "spring.ai.openai.base-url=https://example.invalid/v1",
                        "spring.ai.openai.chat.options.model=ignored-model",
                        "spring.ai.openai.chat.options.max-tokens=512")
                .run(result -> {
                    assertNull(result.getStartupFailure());
                    ModelProperties properties = result.getBean(ModelProperties.class);
                    assertFalse(properties.isEnabled());
                    assertNull(properties.getApiKey());
                    assertNull(properties.getModel());
                    assertEquals("https://api.openai.com/v1", properties.getBaseUrl());
                    assertEquals(4096, properties.getMaxOutputTokens());
                    assertTrue(result.getBeansOfType(ModelGateway.class).isEmpty());
                });
    }

    @Test
    void springAiCredentialsDoNotSatisfyEnabledAgentModel() {
        context.withPropertyValues("agent.model.enabled=true", "agent.model.model=local-test-model",
                        "spring.ai.openai.api-key=ignored-key")
                .run(result -> assertNotNull(result.getStartupFailure()));
    }

    @Test
    void customEstimatorBeanIsUsedByTheConfiguredGateway() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        TokenEstimator estimator = (model, json) -> {
            assertEquals("local-test-model", model);
            assertTrue(json.contains("中文目标"));
            calls.incrementAndGet();
            return 120;
        };
        context.withPropertyValues("agent.model.enabled=true", "agent.model.api-key=local-test-key",
                        "agent.model.model=local-test-model")
                .withBean(TokenEstimator.class, () -> estimator).run(result -> {
                    assertNull(result.getStartupFailure());
                    assertSame(estimator, result.getBean(TokenEstimator.class));
                    assertEquals(1, result.getBeansOfType(TokenEstimator.class).size());
                    try (var session = result.getBean(ModelGateway.class).openSession("rules", "中文目标", java.util.List.of())) {
                        assertEquals(120 + 32 * 3, session.estimatedInputTokens());
                    }
                    assertEquals(1, calls.get());
                });
    }

    @Test
    void defaultEstimatorUsesCharacterWeightedWithoutEnablingTheModel() {
        context.run(result -> {
            assertNull(result.getStartupFailure());
            // "中文" has 2 CJK characters: 2 * 0.70 * 1.15 = 1.61 -> ceil is 2 tokens (not legacy 6 bytes)
            assertEquals(2, result.getBean(TokenEstimator.class).estimate("model", "中文"));
        });
    }

    @Test
    void canConfigureConservativeUtf8Estimator() {
        context.withPropertyValues("agent.model.token-estimator.type=conservative-utf8")
                .run(result -> {
                    assertNull(result.getStartupFailure());
                    // In UTF-8, "中文" takes 6 bytes
                    assertEquals(6, result.getBean(TokenEstimator.class).estimate("model", "中文"));
                });
    }

    @Test
    void canConfigureCustomCharacterWeights() {
        context.withPropertyValues(
                "agent.model.token-estimator.ascii-weight=0.5",
                "agent.model.token-estimator.non-ascii-weight=1.0",
                "agent.model.token-estimator.safety-buffer=1.0"
        ).run(result -> {
            assertNull(result.getStartupFailure());
            // 2 CJK chars * 1.0 = 2 tokens
            assertEquals(2, result.getBean(TokenEstimator.class).estimate("model", "中文"));
            // 5 ASCII chars * 0.5 = 2.5 -> ceil is 3 tokens
            assertEquals(3, result.getBean(TokenEstimator.class).estimate("model", "Hello"));
        });
    }

    @Test
    void doesNotReplaceRunnerTestGateway() {
        ModelGateway supplied = (instructions, goal, tools) -> null;
        context.withBean(ModelGateway.class, () -> supplied)
                .run(result -> assertSame(supplied, result.getBean(ModelGateway.class)));
    }
}
