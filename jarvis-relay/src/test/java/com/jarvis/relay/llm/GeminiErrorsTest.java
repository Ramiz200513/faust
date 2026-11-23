package com.jarvis.relay.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Причина ошибки Gemini для журнала: служебные поля Google — да, данные запроса — нет. */
class GeminiErrorsTest {

    @Test
    void quotaError_isExplained() {
        // Форма ответа Google при исчерпанной квоте (сокращено).
        String body = """
                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
                  "message":"You exceeded your current quota, please check your plan and billing details.",
                  "details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
                    "violations":[{"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                                   "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}
                """;
        String d = GeminiErrors.describe(429, body);
        assertTrue(d.startsWith("Gemini HTTP 429 RESOURCE_EXHAUSTED: You exceeded your current quota"), d);
        assertTrue(d.contains("[квота: GenerateRequestsPerDayPerProjectPerModel-FreeTier]"), d);
    }

    @Test
    void notJson_orEmpty_onlyCode() {
        assertEquals("Gemini HTTP 502", GeminiErrors.describe(502, "<html>Bad gateway</html>"));
        assertEquals("Gemini HTTP 500", GeminiErrors.describe(500, null));
    }

    @Test
    void longMessage_isCut_andRequestIsNeverCopied() {
        String body = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"" + "x".repeat(500) + "\"},"
                      + "\"request\":{\"prompt\":\"секретная фраза человека\"}}";
        String d = GeminiErrors.describe(400, body);
        assertTrue(d.length() < 260, d);
        assertFalse(d.contains("секретная"), "поля вне error.status/message/details в журнал не попадают");
    }
}
