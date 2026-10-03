package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 采集主流程与幂等边界：UTC 时刻固化、同参重放、异参 409、编号唯一、失败不消耗请求键。
 */
class CollectFlowTest extends BaseApiTest {

    private static final Instant FIXED = Instant.parse("2026-03-01T10:15:30Z");

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void fixClock() {
        when(clock.instant()).thenReturn(FIXED);
    }

    @Test
    void collectFixesStratumGridAndUtcTime() throws Exception {
        registerStratum("SITE-T", "L");
        postJson("/api/samples/collect", collectBody("REQ-FIX-1", "S-001", "L", "G-07"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampleNo").value("S-001"))
                .andExpect(jsonPath("$.siteCode").value("SITE-T"))
                .andExpect(jsonPath("$.stratumNo").value("L"))
                .andExpect(jsonPath("$.gridRef").value("G-07"))
                .andExpect(jsonPath("$.collectedAt").value("2026-03-01T10:15:30Z"))
                .andExpect(jsonPath("$.status").value("COLLECTED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/samples/S-001/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stratumNo").value("L"))
                .andExpect(jsonPath("$.gridRef").value("G-07"))
                .andExpect(jsonPath("$.collectedAt").value("2026-03-01T10:15:30Z"));
    }

    @Test
    void sameRequestIdSameParamsReplaysOriginalResponse() throws Exception {
        registerStratum("SITE-T", "L");
        Map<String, Object> body = collectBody("REQ-REPLAY-1", "S-100", "L", "G-07");
        String first = postJson("/api/samples/collect", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postJson("/api/samples/collect", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second, "同参重放应返回首次响应原文");
        assertEquals(1, countRows("sample"), "重放不得产生第二条样本");
        assertEquals(1, countRows("request_log"), "同一 requestId 只落一条幂等记录");
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        registerStratum("SITE-T", "L");
        postJson("/api/samples/collect", collectBody("REQ-DIFF-1", "S-101", "L", "G-01"))
                .andExpect(status().isCreated());
        postJson("/api/samples/collect", collectBody("REQ-DIFF-1", "S-102", "L", "G-02"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("参数不一致")));
    }

    @Test
    void duplicateSampleNoReturns409() throws Exception {
        registerStratum("SITE-T", "L");
        collect("REQ-A1", "S-200", "L", "G-01");
        postJson("/api/samples/collect", collectBody("REQ-A2", "S-200", "L", "G-09"))
                .andExpect(status().isConflict());
    }

    @Test
    void sameNumberDifferentStratumIsNotSameItem() throws Exception {
        registerStratum("SITE-T", "L");
        registerStratum("SITE-T", "M");
        collect("REQ-B1", "S-300", "L", "G-01");
        postJson("/api/samples/collect", collectBody("REQ-B2", "S-300", "M", "G-01"))
                .andExpect(status().isConflict());
        assertEquals(1, countRows("sample"));
    }

    @Test
    void unregisteredStratumReturns422AndFailedRequestDoesNotConsumeKey() throws Exception {
        Map<String, Object> body = collectBody("REQ-C1", "S-400", "L", "G-01");
        postJson("/api/samples/collect", body).andExpect(status().isUnprocessableEntity());
        assertEquals(0, countRows("request_log"), "失败事务不得消耗请求键");
        registerStratum("SITE-T", "L");
        postJson("/api/samples/collect", body).andExpect(status().isCreated());
    }
}