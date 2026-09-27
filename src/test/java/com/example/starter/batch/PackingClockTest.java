package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可控时钟测试：用固定 UTC Clock 替换系统时钟，验证封箱/作废时间戳口径统一，
 * 以及历史时间窗在固定时刻上的左闭右开边界（不依赖真实时间与睡眠）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PackingClockTest {

    static final Instant FIXED = Instant.parse("2026-06-01T08:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM label_occupancy");
        jdbc.update("DELETE FROM packing_record");
        jdbc.update("DELETE FROM packaging_plan");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
    }

    @Test
    void packingTimestampsUseFixedUtcClock_andWindowBoundsAreHalfOpen() throws Exception {
        String batchKey = "BK-CLK-" + UUID.randomUUID().toString().substring(0, 8);
        createAndPass(batchKey);
        registerPlan(batchKey);

        MvcResult packResult = mockMvc.perform(post("/api/batches/" + batchKey + "/packings")
                        .header("X-Actor-Id", "w")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PACK-1\",\"items\":[{\"labelNo\":1000,\"quantity\":10}]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode pack = objectMapper.readTree(packResult.getResponse().getContentAsString());
        assertEquals(FIXED.toString(), pack.path("packedAt").asText(), "封箱时间取注入的 UTC 时钟");
        long packingId = pack.path("packed").get(0).path("packingId").asLong();

        MvcResult voidResult = mockMvc.perform(post("/api/batches/" + batchKey
                                + "/packings/" + packingId + "/void")
                        .header("X-Actor-Id", "boss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-V-1\",\"reason\":\"固定时刻作废\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode voided = objectMapper.readTree(voidResult.getResponse().getContentAsString());
        assertEquals(FIXED.toString(), voided.path("voidedAt").asText());

        // 左闭：from=FIXED 包含该时刻事件
        JsonNode inclusive = history(batchKey, FIXED.toString(), "2026-06-01T08:00:01Z");
        assertEquals(2, inclusive.path("eventCount").asInt(), "左闭：起点时刻的封箱与作废均计入");
        assertEquals(1L, inclusive.path("elapsedSeconds").asLong());

        // 右开：to=FIXED 排除该时刻事件
        JsonNode exclusive = history(batchKey, "2026-06-01T07:59:59Z", FIXED.toString());
        assertEquals(0, exclusive.path("eventCount").asInt(), "右开：终点时刻的事件不计入");

        // 起点不早于终点 → 400
        mockMvc.perform(get("/api/batches/" + batchKey + "/packing/history")
                        .param("from", "2026-06-01T08:00:00Z")
                        .param("to", "2026-06-01T08:00:00Z"))
                .andExpect(status().isBadRequest());

        // 明细中的封箱/作废时间精度保持到固定 instant，null 含义稳定
        JsonNode record = detailRecord(batchKey, packingId);
        assertEquals(FIXED.toString(), record.path("packedAt").asText());
        assertEquals(FIXED.toString(), record.path("voidedAt").asText());
        assertEquals("boss", record.path("voidedBy").asText());
    }

    private void createAndPass(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C\",\"batchKey\":\"" + batchKey
                                + "\",\"productCode\":\"p\",\"batchNo\":\"n\","
                                + "\"producedAt\":\"2026-01-02T03:04:05Z\",\"requiredTests\":[\"t1\"]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T\",\"testKey\":\"TK-1\",\"testItem\":\"t1\","
                                + "\"result\":\"PASS\",\"inspector\":\"insp\"}"))
                .andExpect(status().isCreated());
    }

    private void registerPlan(String batchKey) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/packing-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-PLAN\",\"plannedQuantity\":10,"
                                + "\"labelStart\":1000,\"labelEnd\":1009}"))
                .andExpect(status().isCreated());
    }

    private JsonNode history(String batchKey, String from, String to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/packing/history")
                        .param("from", from).param("to", to))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode detailRecord(String batchKey, long packingId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/packing"))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = objectMapper.readTree(result.getResponse().getContentAsString()).path("records");
        for (JsonNode record : records) {
            if (record.path("packingId").asLong() == packingId) {
                return record;
            }
        }
        throw new AssertionError("未找到封箱版本 " + packingId);
    }
}
