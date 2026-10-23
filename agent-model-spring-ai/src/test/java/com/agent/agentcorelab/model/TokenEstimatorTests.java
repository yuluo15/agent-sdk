package com.agent.agentcorelab.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenEstimator Specification and Character-Weighted Tests")
class TokenEstimatorTests {

    private final TokenEstimator smartEstimator = TokenEstimator.characterWeighted();
    private final TokenEstimator conservativeEstimator = TokenEstimator.conservativeUtf8();

    @Test
    @DisplayName("Null or empty input returns 0 tokens")
    void testNullOrEmptyReturnsZero() {
        assertEquals(0L, smartEstimator.estimate("deepseek-chat", null));
        assertEquals(0L, smartEstimator.estimate("deepseek-chat", ""));
        assertEquals(0L, conservativeEstimator.estimate("deepseek-chat", null));
        assertEquals(0L, conservativeEstimator.estimate("deepseek-chat", ""));
    }

    @Test
    @DisplayName("ASCII text estimates at approximately 4 characters per token")
    void testAsciiTextEstimation() {
        String text = "This is a standard English troubleshooting instruction text for testing.";
        // 72 characters: 72 * 0.25 = 18.0 -> 18.0 * 1.15 = 20.7 -> ceil = 21 tokens
        long estimated = smartEstimator.estimate("deepseek-chat", text);

        assertTrue(estimated >= 15 && estimated <= 25,
                "Expected around 21 tokens for 72 ASCII chars, got: " + estimated);
    }

    @Test
    @DisplayName("Chinese text eliminates the 3x byte inflation compared to legacy conservative UTF-8")
    void testChineseTextEstimationEliminatesByteInflation() {
        String chineseSentence = "根据知识库排查发现该VPN服务握手超时系会话凭证过期所致，建议执行会话重置。";
        // 36 characters (34 CJK ideographs + 2 full-width punctuation characters)
        // In UTF-8: 36 * 3 = 108 bytes
        long legacyTokens = conservativeEstimator.estimate("deepseek-chat", chineseSentence);
        assertEquals(chineseSentence.getBytes(StandardCharsets.UTF_8).length, legacyTokens);
        assertEquals(108L, legacyTokens, "Legacy estimator counts 108 bytes as 108 tokens");

        // With characterWeighted: (2 ASCII 'VPN' is 3 chars? No, V, P, N are 3 chars)
        // 33 non-ASCII chars * 0.7 + 3 ASCII chars * 0.25 = 23.1 + 0.75 = 23.85 * 1.15 = 27.42 -> ceil = 28
        long smartTokens = smartEstimator.estimate("deepseek-chat", chineseSentence);
        assertTrue(smartTokens >= 25 && smartTokens <= 32);

        // Verification: smart estimation is approximately 1/4 to 1/3 of the naive byte count
        assertTrue(smartTokens < legacyTokens / 3,
                "Smart estimation should be roughly 1/3 of legacy UTF-8 bytes count");
    }

    @Test
    @DisplayName("Mixed Chinese, English, numbers, and punctuation estimation")
    void testMixedChineseAndEnglishEstimation() {
        String mixed = "Error 404: 数据库连接超时，请检查 host: db.internal.net 端口 3306 配置。";
        long estimated = smartEstimator.estimate("gpt-4o", mixed);
        // Smart weighted estimation is between 20 and 28 tokens
        assertEquals(25L, estimated);

        // In legacy UTF-8, non-ASCII takes 3 bytes each
        long legacy = conservativeEstimator.estimate("gpt-4o", mixed);
        assertEquals((long) mixed.getBytes(StandardCharsets.UTF_8).length, legacy);
        assertTrue(estimated < legacy / 2);
    }

    @Test
    @DisplayName("Single character edge cases produce at least 1 token")
    void testSingleCharacterEdgeCases() {
        assertEquals(1L, smartEstimator.estimate("deepseek-chat", "a"));
        assertEquals(1L, smartEstimator.estimate("deepseek-chat", "中"));
        assertEquals(1L, smartEstimator.estimate("deepseek-chat", " "));
        assertEquals(1L, smartEstimator.estimate("deepseek-chat", "，"));
    }

    @Test
    @DisplayName("Surrogate pairs and emojis are gracefully estimated")
    void testSurrogatePairsAndEmojis() {
        // Emoji 🚀 is encoded as surrogate pair in Java String (length 2, both > 127)
        String emojiText = "Deployment success! 🚀🎉";
        // 20 chars in Java String: 20 chars
        long estimated = smartEstimator.estimate("deepseek-chat", emojiText);
        assertTrue(estimated >= 5 && estimated <= 12);
    }

    @Test
    @DisplayName("Conservative UTF-8 preserves strict byte-counting backwards compatibility")
    void testConservativeUtf8BackwardsCompatibility() {
        TokenEstimator estimator = TokenEstimator.conservativeUtf8();
        for (String sample : new String[]{"", "abc", "北京", "Hello 世界 \uD801\uDC37"}) {
            assertEquals((long) sample.getBytes(StandardCharsets.UTF_8).length, estimator.estimate("any-model", sample));
        }
    }

    @Test
    @DisplayName("Configurable weights and safety buffer are respected")
    void testConfigurableWeights() {
        // Custom: 0.5 for ASCII, 1.0 for non-ASCII, 1.0 buffer (no extra cushion)
        TokenEstimator custom = TokenEstimator.characterWeighted(0.5, 1.0, 1.0);

        // "Hello" (5 ASCII): 5 * 0.5 = 2.5 -> ceil = 3
        assertEquals(3L, custom.estimate("custom-model", "Hello"));

        // "中国" (2 CJK): 2 * 1.0 = 2.0 -> 2
        assertEquals(2L, custom.estimate("custom-model", "中国"));

        // "Hi 中国": ASCII 3 ('H','i',' '), Non-ASCII 2 ('中','国') -> 3*0.5 + 2*1.0 = 3.5 -> ceil = 4
        assertEquals(4L, custom.estimate("custom-model", "Hi 中国"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, -100.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("Invalid weights or buffer values throw IllegalArgumentException")
    void testInvalidParameters(double invalidValue) {
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.characterWeighted(invalidValue, 0.7, 1.15));
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.characterWeighted(0.25, invalidValue, 1.15));
        assertThrows(IllegalArgumentException.class, () -> TokenEstimator.characterWeighted(0.25, 0.7, invalidValue));
    }

    @Test
    @DisplayName("defaultEstimator returns characterWeighted instance")
    void testDefaultEstimator() {
        TokenEstimator def = TokenEstimator.defaultEstimator();
        assertNotNull(def);
        assertEquals(smartEstimator.estimate("model", "测试中文"), def.estimate("model", "测试中文"));
    }
}
