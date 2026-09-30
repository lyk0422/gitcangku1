package com.example.starter.integration;

import com.example.starter.ResetDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端测试：完整 Spring 上下文 + 真实 HTTP 入口 + 真实 H2（MODE=MySQL）。
 * 覆盖题干合成示例：仪器 T 20:00-21:00 已分配；撤销后 20:10-20:50 的首个
 * 合格候补接替；另一个重叠批准返回 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabase
class ObservationEndToEndIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String applyJson(String no, String project, String instrument,
                             String start, String end, int minutes) {
        return """
                {"requestNo":"%s","projectId":"%s","instrumentId":"%s",
                 "startAt":"%s","endAt":"%s","durationMinutes":%d}"""
                .formatted(no, project, instrument, start, end, minutes);
    }

    @Test
    @DisplayName("合成示例端到端：分配→重叠409→撤销→候补接替→查询一致")
    void syntheticScenarioOverHttp() throws Exception {
        // 1) R-1 申请并批准：仪器 T，20:00-21:00
        mockMvc.perform(post("/api/observations/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("R-1", "P1", "T",
                                "2026-09-30T20:00:00Z", "2026-09-30T21:00:00Z", 60)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/observations/requests/R-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"));

        // 2) 候补 W-1：20:10-20:50；重叠申请 R-2 批准返回 409
        mockMvc.perform(post("/api/observations/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("W-1", "P2", "T",
                                "2026-09-30T20:10:00Z", "2026-09-30T20:50:00Z", 40)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("R-2", "P3", "T",
                                "2026-09-30T20:30:00Z", "2026-09-30T21:30:00Z", 60)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations/requests/R-2/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_OVERLAP"));

        // 3) 撤销 R-1，W-1 接替 20:10-20:50
        mockMvc.perform(post("/api/observations/requests/R-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"R-1","projectId":"P1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled.status").value("CANCELLED"))
                .andExpect(jsonPath("$.promoted.requestNo").value("W-1"))
                .andExpect(jsonPath("$.promoted.status").value("PROMOTED"))
                .andExpect(jsonPath("$.promoted.promotedFromRequestNo").value("R-1"))
                .andExpect(jsonPath("$.gapStartAt").value("2026-09-30T20:00:00Z"))
                .andExpect(jsonPath("$.gapEndAt").value("2026-09-30T21:00:00Z"));

        // 4) 时间轴只剩 W-1；接替事件可查；候补队列不再含 W-1
        mockMvc.perform(get("/api/observations/instruments/T/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestNo").value("W-1"))
                .andExpect(jsonPath("$[0].fromPromotion").value(true))
                .andExpect(jsonPath("$[0].promotedFromRequestNo").value("R-1"));

        mockMvc.perform(get("/api/observations/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cancelledRequestNo").value("R-1"))
                .andExpect(jsonPath("$[0].promotedRequestNo").value("W-1"));

        MvcResult waitlistResult = mockMvc.perform(get("/api/observations/instruments/T/waitlist"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode waitlist = objectMapper.readTree(waitlistResult.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(
                waitlist.get("waiting").findValuesAsText("requestNo").stream()
                        .noneMatch("W-1"::equals));

        // 5) 重复撤销返回 409，不再次推进
        mockMvc.perform(post("/api/observations/requests/R-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestNo":"R-1","projectId":"P1"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
    }

    @Test
    @DisplayName("非法时间窗口返回422并指出原因")
    void invalidWindowOverHttp() throws Exception {
        mockMvc.perform(post("/api/observations/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson("BAD-1", "P1", "T",
                                "2026-09-30T21:00:00Z", "2026-09-30T20:00:00Z", 60)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("END_NOT_AFTER_START"));
    }
}
