package com.example.starter.bridge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 许可 REST API 端到端测试：合成 34 吨车 09:00 获许可、09:05 冲突 409、09:12 出桥前不误放行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class PermitApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM bridge_permit");
        jdbcTemplate.update("DELETE FROM bridge_rejection");
        clock.set(TestClockConfiguration.BASE_TIME);
    }

    private static String applyBody(String requestId, String plate, int massKg, String plannedAt) {
        return "{\"requestId\":\"" + requestId + "\",\"plateNo\":\"" + plate
                + "\",\"totalMassKg\":" + massKg + ",\"plannedEntryAt\":\"" + plannedAt + "\"}";
    }

    private static String opBody(String requestId) {
        return "{\"requestId\":\"" + requestId + "\"}";
    }

    @Test
    void syntheticScenarioAcrossPermitLifecycle() throws Exception {
        clock.set(Instant.parse("2026-10-04T08:50:00Z"));

        MvcResult created = mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("req-a", "CAR-34T", 34000, "2026-10-04T09:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.windowStartAt").value("2026-10-04T09:00:00Z"))
                .andExpect(jsonPath("$.windowEndAt").value("2026-10-04T09:10:00Z"))
                .andReturn();
        long permitId = Long.parseLong(
                com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

        mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("req-b", "CAR-33T", 33000, "2026-10-04T09:05:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_OVERLAP"));

        clock.set(Instant.parse("2026-10-04T09:03:00Z"));
        mockMvc.perform(post("/api/bridge/permits/{id}/entry", permitId)
                        .contentType(MediaType.APPLICATION_JSON).content(opBody("op-entry-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENTERED"))
                .andExpect(jsonPath("$.actualEntryAt").value("2026-10-04T09:03:00Z"));

        mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("req-b2", "CAR-33T", 33000, "2026-10-04T09:11:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_OVERLAP"));

        clock.set(Instant.parse("2026-10-04T09:12:00Z"));
        mockMvc.perform(post("/api/bridge/permits/{id}/exit", permitId)
                        .contentType(MediaType.APPLICATION_JSON).content(opBody("op-exit-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXITED"))
                .andExpect(jsonPath("$.actualExitAt").value("2026-10-04T09:12:00Z"));

        mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("req-b3", "CAR-33T", 33000, "2026-10-04T09:12:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/bridge/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxTotalMassKg").value(35000))
                .andExpect(jsonPath("$.crossingMinutes").value(10))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].status").value("EXITED"))
                .andExpect(jsonPath("$.entries[0].occupiedFrom").value("2026-10-04T09:03:00Z"))
                .andExpect(jsonPath("$.entries[0].occupiedTo").value("2026-10-04T09:12:00Z"))
                .andExpect(jsonPath("$.rejections.length()").value(2))
                .andExpect(jsonPath("$.rejections[0].reasonCode").value("WINDOW_OVERLAP"));
    }

    @Test
    void idempotentReplayReturnsOriginalPermit() throws Exception {
        String body = applyBody("req-a", "CAR-A", 30000, "2026-10-04T09:00:00Z");
        MvcResult created = mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String firstId = com.jayway.jsonpath.JsonPath
                .read(created.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.parseLong(firstId)));

        mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("req-a", "CAR-A", 31000, "2026-10-04T09:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void validationAndNotFoundErrors() throws Exception {
        mockMvc.perform(post("/api/bridge/permits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plateNo\":\"CAR-A\",\"totalMassKg\":30000,"
                                + "\"plannedEntryAt\":\"2026-10-04T09:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/bridge/permits/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PERMIT_NOT_FOUND"));
    }
}