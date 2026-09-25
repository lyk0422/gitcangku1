package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备时钟偏移登记/修改 API 测试：区间校验、参数校验与幂等边界（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceOffsetApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_reorder");
        jdbcTemplate.update("DELETE FROM device_observation");
        jdbcTemplate.update("DELETE FROM device_offset");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    private ResultActions register(String deviceId, String requestId,
                                   String effectiveFromUtc, int offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        return mockMvc.perform(post("/api/devices/{deviceId}/offsets", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions modify(String deviceId, String requestId,
                                 String effectiveFromUtc, int offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        return mockMvc.perform(put("/api/devices/{deviceId}/offsets", deviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions listOffsets(String deviceId) throws Exception {
        return mockMvc.perform(get("/api/devices/{deviceId}/offsets", deviceId));
    }

    private int offsetCount(String deviceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_offset WHERE device_id = ?", Integer.class, deviceId);
        return count == null ? 0 : count;
    }

    // ---------- 登记与区间校验 ----------

    @Test
    void registerReturnsCreatedAndListsInEffectiveFromOrder() throws Exception {
        register("dev-1", "req-o1", "2026-09-25T06:00:00Z", 30).andExpect(status().isCreated());
        register("dev-1", "req-o2", "2026-09-25T00:00:00Z", -15)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.effectiveFromUtc").value("2026-09-25T00:00:00Z"))
                .andExpect(jsonPath("$.offsetSeconds").value(-15));

        listOffsets("dev-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].effectiveFromUtc").value("2026-09-25T00:00:00Z"))
                .andExpect(jsonPath("$[0].offsetSeconds").value(-15))
                .andExpect(jsonPath("$[1].effectiveFromUtc").value("2026-09-25T06:00:00Z"))
                .andExpect(jsonPath("$[1].offsetSeconds").value(30));
    }

    @Test
    void registerOverlappingEffectiveFromReturns409() throws Exception {
        register("dev-1", "req-o3", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        // 同一设备相同生效起始时刻：区间重叠
        register("dev-1", "req-o4", "2026-09-25T00:00:00Z", 20)
                .andExpect(status().isConflict());
        // 原记录不被改写
        listOffsets("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offsetSeconds").value(10));
        // 不同设备同一起始时刻不冲突
        register("dev-2", "req-o5", "2026-09-25T00:00:00Z", 20).andExpect(status().isCreated());
        assertThat(offsetCount("dev-1")).isEqualTo(1);
        assertThat(offsetCount("dev-2")).isEqualTo(1);
    }

    @Test
    void registerWithOutOfRangeSecondsReturns400() throws Exception {
        register("dev-1", "req-o6", "2026-09-25T00:00:00Z", 86401).andExpect(status().isBadRequest());
        register("dev-1", "req-o7", "2026-09-25T00:00:00Z", -86401).andExpect(status().isBadRequest());
        assertThat(offsetCount("dev-1")).isZero();
        // 边界值 ±86400 合法
        register("dev-1", "req-o8", "2026-09-25T00:00:00Z", 86400).andExpect(status().isCreated());
        register("dev-1", "req-o9", "2026-09-26T00:00:00Z", -86400).andExpect(status().isCreated());
    }

    @Test
    void registerWithInvalidInstantReturns400() throws Exception {
        register("dev-1", "req-o10", "2026-09-25 10:00:00", 10).andExpect(status().isBadRequest());
        register("dev-1", "req-o11", "not-a-time", 10).andExpect(status().isBadRequest());
        assertThat(offsetCount("dev-1")).isZero();
    }

    // ---------- 修改 ----------

    @Test
    void modifyUpdatesOffsetSeconds() throws Exception {
        register("dev-1", "req-m1", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        modify("dev-1", "req-m2", "2026-09-25T00:00:00Z", -42)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetSeconds").value(-42))
                .andExpect(jsonPath("$.effectiveFromUtc").value("2026-09-25T00:00:00Z"));
        listOffsets("dev-1")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].offsetSeconds").value(-42));
    }

    @Test
    void modifyMissingOffsetReturns404() throws Exception {
        modify("dev-1", "req-m3", "2026-09-25T00:00:00Z", 10)
                .andExpect(status().isNotFound());
        register("dev-1", "req-m4", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        modify("dev-1", "req-m5", "2026-09-25T01:00:00Z", 20)
                .andExpect(status().isNotFound());
        listOffsets("dev-1").andExpect(jsonPath("$[0].offsetSeconds").value(10));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        register("dev-1", "req-i1", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        register("dev-1", "req-i1", "2026-09-25T00:00:00Z", 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.offsetSeconds").value(10));
        assertThat(offsetCount("dev-1")).isEqualTo(1);

        modify("dev-1", "req-i2", "2026-09-25T00:00:00Z", 20).andExpect(status().isOk());
        modify("dev-1", "req-i2", "2026-09-25T00:00:00Z", 20)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetSeconds").value(20));
        listOffsets("dev-1").andExpect(jsonPath("$[0].offsetSeconds").value(20));
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        register("dev-1", "req-i3", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        register("dev-1", "req-i3", "2026-09-25T01:00:00Z", 10).andExpect(status().isConflict());
        register("dev-1", "req-i3", "2026-09-25T00:00:00Z", 99).andExpect(status().isConflict());
        assertThat(offsetCount("dev-1")).isEqualTo(1);
        listOffsets("dev-1").andExpect(jsonPath("$[0].offsetSeconds").value(10));
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        register("dev-1", "req-f1", "2026-09-25T00:00:00Z", 10).andExpect(status().isCreated());
        // 重叠失败：不占键
        register("dev-1", "req-f2", "2026-09-25T00:00:00Z", 20).andExpect(status().isConflict());
        register("dev-1", "req-f2", "2026-09-25T02:00:00Z", 20).andExpect(status().isCreated());
        // 404 失败：不占键
        modify("dev-1", "req-f3", "2026-09-25T09:00:00Z", 5).andExpect(status().isNotFound());
        modify("dev-1", "req-f3", "2026-09-25T00:00:00Z", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsetSeconds").value(5));
    }
}
