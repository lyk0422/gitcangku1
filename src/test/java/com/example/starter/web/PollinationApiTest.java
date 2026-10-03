package com.example.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
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

/**
 * HTTP 接口测试：验证状态码映射（201/200/400/404/409/422）与响应体（真实 H2，可控时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class PollinationApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM pollination_operation");
        jdbc.update("DELETE FROM inflorescence");
        jdbc.update("DELETE FROM pollen_batch");
        clock.set(Instant.parse("2026-02-01T10:00:00Z"));
    }

    private long newInflorescence(String code, String firstOpenAt) throws Exception {
        String body = "{\"code\":\"" + code + "\",\"firstOpenAt\":\"" + firstOpenAt + "\"}";
        String response = mvc.perform(post("/api/inflorescences")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.windowEndAt").exists())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void newBatch(String batchNo) throws Exception {
        mvc.perform(post("/api/pollen-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"" + batchNo + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private String pollinateBody(String requestId, String batchNo, String technician) {
        return "{\"requestId\":\"" + requestId + "\",\"pollenBatchNo\":\"" + batchNo
                + "\",\"technicianId\":\"" + technician + "\"}";
    }

    @Test
    void fullFlowOverHttp() throws Exception {
        long flowerId = newInflorescence("INF-API-1", "2026-02-01T10:00:00Z");
        newBatch("PB-API-1");

        String operation = mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-1", "PB-API-1", "tech-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.batchNo").value("PB-API-1"))
                .andExpect(jsonPath("$.operatedAt").value("2026-02-01T10:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        long operationId = Long.parseLong(operation.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-2", "PB-API-1", "tech-b")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EFFECTIVE_OPERATION"));

        mvc.perform(post("/api/pollinations/{id}/confirmation", operationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"cfm-api-1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED_SUCCESS"))
                .andExpect(jsonPath("$.confirmedBatchNo").value("PB-API-1"));

        mvc.perform(post("/api/pollinations/{id}/revocation", operationId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_ALREADY_CONFIRMED"));

        mvc.perform(get("/api/inflorescences/{id}/timeline", flowerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inflorescence.firstOpenAt").value("2026-02-01T10:00:00Z"))
                .andExpect(jsonPath("$.inflorescence.windowEndAt").value("2026-02-02T10:00:00Z"))
                .andExpect(jsonPath("$.operations[0].technicianId").value("tech-a"))
                .andExpect(jsonPath("$.operations[0].confirmedBatchNo").value("PB-API-1"));
    }

    @Test
    void businessErrorsMapToExpectedStatus() throws Exception {
        long flowerId = newInflorescence("INF-API-2", "2026-02-01T10:00:00Z");
        newBatch("PB-API-2");

        clock.set(Instant.parse("2026-02-02T10:00:00Z"));
        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-3", "PB-API-2", "tech-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_EXPIRED"));

        clock.set(Instant.parse("2026-02-01T12:00:00Z"));
        mvc.perform(post("/api/pollen-batches/{batchNo}/disabling", "PB-API-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-4", "PB-API-2", "tech-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("POLLEN_BATCH_DISABLED"));

        newBatch("PB-API-3");
        mvc.perform(post("/api/inflorescences/{id}/removal", flowerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedAt").exists());
        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-5", "PB-API-3", "tech-a")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INFLORESCENCE_REMOVED"));

        mvc.perform(post("/api/inflorescences/{id}/pollinations", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-6", "PB-API-3", "tech-a")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INFLORESCENCE_NOT_FOUND"));

        mvc.perform(post("/api/inflorescences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void idempotentReplayOverHttp() throws Exception {
        long flowerId = newInflorescence("INF-API-3", "2026-02-01T10:00:00Z");
        newBatch("PB-API-4");

        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-7", "PB-API-4", "tech-a")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-7", "PB-API-4", "tech-a")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("req-api-7"));
        mvc.perform(post("/api/inflorescences/{id}/pollinations", flowerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pollinateBody("req-api-7", "PB-API-4", "tech-b")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }
}