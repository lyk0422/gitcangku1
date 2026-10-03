package com.example.starter;

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
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

/** 主流程HTTP测试：合成10:00完成液型A除冰，10:19可确认，10:20被拒并重新申请。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
@Sql(scripts = "/cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DeicingFlowTest {

    private static final Instant T_1000 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(T_1000);
    }

    @Test
    void fluidACompletedAt1000ClearedAt1019() throws Exception {
        mockMvc.perform(post("/api/deicing/slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"apply-1\",\"flightId\":1,\"vehicleId\":1,"
                        + "\"slotStart\":\"2026-01-01T09:30:00Z\",\"slotEnd\":\"2026-01-01T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"));

        mockMvc.perform(post("/api/deicing/slots/1/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fluidType\":\"A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fluidType").value("A"))
                .andExpect(jsonPath("$.completedAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.deadlineAt").value("2026-01-01T10:20:00Z"));

        clock.setInstant(Instant.parse("2026-01-01T10:19:00Z"));
        mockMvc.perform(post("/api/deicing/takeoff-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"takeoff-1\",\"flightId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.completionId").value(1));

        mockMvc.perform(get("/api/deicing/vehicles/1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vehicleCode").value("DEICE-01"))
                .andExpect(jsonPath("$.entries[0].slot.status").value("COMPLETED"))
                .andExpect(jsonPath("$.entries[0].completion.fluidType").value("A"))
                .andExpect(jsonPath("$.entries[0].completion.deadlineAt")
                        .value("2026-01-01T10:20:00Z"));

        mockMvc.perform(get("/api/deicing/flights/1/clearances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CLEARED"))
                .andExpect(jsonPath("$[0].confirmedAt").value("2026-01-01T10:19:00Z"));
    }

    @Test
    void rejectedAt1020ThenReapplyAndCleared() throws Exception {
        mockMvc.perform(post("/api/deicing/slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"apply-1\",\"flightId\":2,\"vehicleId\":1,"
                        + "\"slotStart\":\"2026-01-01T09:30:00Z\",\"slotEnd\":\"2026-01-01T10:00:00Z\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/deicing/slots/1/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fluidType\":\"A\"}"))
                .andExpect(status().isOk());

        // 截止时刻10:20及之后确认被拒，拒绝历史落库
        clock.setInstant(T_1000.plusSeconds(20 * 60));
        mockMvc.perform(post("/api/deicing/takeoff-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"takeoff-1\",\"flightId\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("超过起飞截止时刻"));

        // 逾期航班重新申请除冰，新作业不覆盖旧完成记录与拒绝历史
        mockMvc.perform(post("/api/deicing/slots").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"apply-2\",\"flightId\":2,\"vehicleId\":1,"
                        + "\"slotStart\":\"2026-01-01T10:20:00Z\",\"slotEnd\":\"2026-01-01T10:40:00Z\"}"))
                .andExpect(status().isOk());
        clock.setInstant(Instant.parse("2026-01-01T10:30:00Z"));
        mockMvc.perform(post("/api/deicing/slots/2/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fluidType\":\"B\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deadlineAt").value("2026-01-01T11:05:00Z"));

        clock.setInstant(Instant.parse("2026-01-01T10:50:00Z"));
        mockMvc.perform(post("/api/deicing/takeoff-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"takeoff-2\",\"flightId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.completionId").value(2));

        mockMvc.perform(get("/api/deicing/flights/2/clearances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("REJECTED"))
                .andExpect(jsonPath("$[1].status").value("CLEARED"));

        mockMvc.perform(get("/api/deicing/vehicles/1/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].completion.fluidType").value("A"))
                .andExpect(jsonPath("$.entries[1].completion.fluidType").value("B"));
    }
}