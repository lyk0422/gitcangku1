package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 幂等与预测修订：同 requestId 同参重放、异参 409；
 * 修订后未开始分配转待重排，已完成保留旧预测证据，过期版本确认被拒。
 */
class IdempotencyAndRevisionTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private long publish(String satellite, String start, String end, String elev, long bytes)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/predictions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(predictionBody(satellite, start, end, elev, bytes)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private long allocate(String requestId, String satellite, String start, String end)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/allocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody(requestId, satellite, STATION_A, start, end)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflict() throws Exception {
        publish("SAT-1", "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z", "8.0", 5_000_000);
        String body = allocateBody("req-1", "SAT-1", STATION_A,
                "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z");
        MvcResult first = mockMvc.perform(post("/api/allocations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asLong();

        // 同参重放：返回同一分配。
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 异参重放：409。
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content(allocateBody("req-1", "SAT-1", STATION_A,
                                "2026-10-04T12:01:00Z", "2026-10-04T12:10:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void completionIsIdempotentAndBoundedByPlannedBytes() throws Exception {
        publish("SAT-1", "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z", "8.0", 5_000_000);
        long id = allocate("req-1", "SAT-1", "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z");

        // 实际字节数超过计划量：409。
        mockMvc.perform(post("/api/allocations/{id}/complete", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"done-1\",\"actualBytes\":6000000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ACTUAL_BYTES_EXCEED_PLANNED"));

        // 正常完成。
        mockMvc.perform(post("/api/allocations/{id}/complete", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"done-1\",\"actualBytes\":4000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.actualBytes").value(4_000_000));

        // 同参重放：返回原结果。
        mockMvc.perform(post("/api/allocations/{id}/complete", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"done-1\",\"actualBytes\":4000000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualBytes").value(4_000_000));

        // 异参重放：409。
        mockMvc.perform(post("/api/allocations/{id}/complete", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"done-1\",\"actualBytes\":3000000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("IDEMPOTENCY_CONFLICT"));

        // 接收实绩查询。
        mockMvc.perform(get("/api/allocations/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualBytes").value(4_000_000))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void revisionFlagsUnstartedAllocationsAndKeepsCompletedEvidence() throws Exception {
        publish("SAT-1", "2026-10-04T12:00:00Z", "2026-10-04T12:10:00Z", "8.0", 5_000_000);
        long toComplete = allocate("req-c", "SAT-1",
                "2026-10-04T12:00:00Z", "2026-10-04T12:05:00Z");
        long toFlag = allocate("req-f", "SAT-1",
                "2026-10-04T12:05:00Z", "2026-10-04T12:10:00Z");
        mockMvc.perform(post("/api/allocations/{id}/complete", toComplete)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"done-c\",\"actualBytes\":1000000}"))
                .andExpect(status().isOk());

        // 修订：窗口收缩为 12:06-12:10，未开始的 12:05-12:10 分配不再满足。
        publish("SAT-1", "2026-10-04T12:06:00Z", "2026-10-04T12:10:00Z", "8.0", 2_000_000);

        // 待重排列表包含未开始分配。
        mockMvc.perform(get("/api/allocations/pending-reassign"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(toFlag))
                .andExpect(jsonPath("$[0].status").value("PENDING_REASSIGN"));

        // 已完成接收保留旧预测证据。
        mockMvc.perform(get("/api/allocations/{id}", toComplete))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.predictionVersion").value(1))
                .andExpect(jsonPath("$.actualBytes").value(1_000_000));

        // 版本查询：v1 已被取代，v2 当前有效。
        mockMvc.perform(get("/api/predictions/SAT-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].superseded").value(true))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].superseded").value(false));

        // 凭过期版本确认：409 STALE_PREDICTION。
        mockMvc.perform(post("/api/allocations").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-stale","satelliteCode":"SAT-1","stationCode":"ST-A",\
                                "slotStartUtc":"2026-10-04T12:06:00Z","slotEndUtc":"2026-10-04T12:10:00Z",\
                                "expectedPredictionVersion":1}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("STALE_PREDICTION"));
    }
}