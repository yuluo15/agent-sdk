package com.agent.agentcorelab;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ModelLoggingConfigurationTests {
    @Test
    void applicationDisablesOnlyThePromptLoggingModelCategory() throws Exception {
        Properties properties = new Properties();
        try (var input = ModelLoggingConfigurationTests.class.getResourceAsStream("/application.properties")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals("OFF", properties.getProperty("logging.level.org.springframework.ai.openai.OpenAiChatModel"));
        assertNull(properties.getProperty("logging.level.root"));
        assertNull(properties.getProperty("logging.level.org.springframework.ai"));
    }
}
