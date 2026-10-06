package com.example.starter.excursion;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越限观测 API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExcursionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM excursion_analysis");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM excursion_version");
        jdbcTemplate.update("DELETE FROM excursion_current");
    }

    private ResultActions create(String requestId, String observationId, String stationId,
                                 String minuteUtc, String reading) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("observationId", observationId);
        body.put("stationId", stationId);
        body.put("minuteUtc", minuteUtc);
        body.put("reading", reading);
        return mockMvc.perform(post("/api/excursions/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String stationId, String minuteUtc, String reading) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("baseVersion", baseVersion);
        body.put("stationId", stationId);
        body.put("minuteUtc", minuteUtc);
        body.put("reading", reading);
        return mockMvc.perform(post("/api/excursions/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/excursions/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/excursions/observations/{id}", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM excursion_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createReturnsVersionOne() throws Exception {
        create("req-x1", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11.5")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("exc-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.stationId").value("ST-A"))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.reading").value("11.5"));
        getCurrent("exc-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value("ST-A"));
        assertThat(versionCount("exc-1")).isEqualTo(1);
    }

    @Test
    void createRejectsNonWholeMinute() throws Exception {
        create("req-x2", "exc-1", "ST-A", "2026-10-07T10:00:30Z", "11")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("exc-1")).isZero();
    }

    @Test
    void createRejectsBlankOperator() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-x3");
        body.put("operator", " ");
        body.put("observationId", "exc-1");
        body.put("stationId", "ST-A");
        body.put("minuteUtc", "2026-10-07T10:00:00Z");
        body.put("reading", "11");
        mockMvc.perform(post("/api/excursions/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("exc-1")).isZero();
    }

    @Test
    void createSameStationSameMinuteReturns409() throws Exception {
        create("req-x4", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 不同 observationId 但同站同分钟：唯一约束冲突
        create("req-x5", "exc-2", "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isConflict());
        // 同站不同分钟可以创建
        create("req-x6", "exc-2", "ST-A", "2026-10-07T10:01:00Z", "12").andExpect(status().isCreated());
        // 不同站同分钟可以创建
        create("req-x7", "exc-3", "ST-B", "2026-10-07T10:00:00Z", "13").andExpect(status().isCreated());
    }

    @Test
    void deleteFreesStationMinuteForNewObservation() throws Exception {
        create("req-x8", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        delete("req-x9", "exc-1", 1).andExpect(status().isOk());
        // 墓碑释放同站同分钟唯一约束：可重新创建
        create("req-x10", "exc-2", "ST-A", "2026-10-07T10:00:00Z", "12").andExpect(status().isCreated());
        getCurrent("exc-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.minuteUtc").doesNotExist());
    }

    // ---------- 三方合并（时刻字段参与完整版本与三方合并） ----------

    @Test
    void mergeMinuteFieldParticipatesInMergeAndVersioning() throws Exception {
        create("req-m1", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 离线端基于版本 1 修改时刻：当前未改，接受候选，版本加一
        merge("req-m2", "exc-1", 1, "ST-A", "2026-10-07T10:05:00Z", "11")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:05:00Z"));
        // 历史版本完整保留原时刻
        mockMvc.perform(get("/api/excursions/observations/{id}/versions/{v}", "exc-1", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:00:00Z"));
        assertThat(versionCount("exc-1")).isEqualTo(2);
    }

    @Test
    void mergeConflictOnMinuteReturns409AndWritesNothing() throws Exception {
        create("req-m3", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        merge("req-m4", "exc-1", 1, "ST-A", "2026-10-07T10:05:00Z", "11").andExpect(status().isOk());
        // 离线端基于版本 1 把时刻改到另一分钟：与服务端版本 2 冲突
        merge("req-m5", "exc-1", 1, "ST-A", "2026-10-07T10:09:00Z", "11")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("minuteUtc"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("exc-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:05:00Z"));
        assertThat(versionCount("exc-1")).isEqualTo(2);
    }

    @Test
    void mergeReadingComparedNumerically() throws Exception {
        create("req-m6", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11.0").andExpect(status().isCreated());
        // 候选 11.00 与基线 11.0 数值相等：视为未改，结果与当前相同，不加版本
        merge("req-m7", "exc-1", 1, "ST-A", "2026-10-07T10:00:00Z", "11.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("11.0"));
        assertThat(versionCount("exc-1")).isEqualTo(1);
    }

    @Test
    void mergeOntoOccupiedStationMinuteReturns409() throws Exception {
        create("req-m8", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        create("req-m9", "exc-2", "ST-A", "2026-10-07T10:01:00Z", "12").andExpect(status().isCreated());
        // 把 exc-2 合并到 exc-1 占用的同站同分钟：唯一约束冲突
        merge("req-m10", "exc-2", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isConflict());
        getCurrent("exc-2")
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:01:00Z"));
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deleteCreatesTombstoneAndRejectsFurtherWrites() throws Exception {
        create("req-d1", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        delete("req-d2", "exc-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.stationId").doesNotExist());
        merge("req-d3", "exc-1", 1, "ST-A", "2026-10-07T10:01:00Z", "12").andExpect(status().isGone());
        delete("req-d4", "exc-1", 2).andExpect(status().isGone());
        delete("req-d5", "exc-1", 5).andExpect(status().isGone());
        assertThat(versionCount("exc-1")).isEqualTo(2);
    }

    @Test
    void deleteWithMismatchedVersionReturns409() throws Exception {
        create("req-d6", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        delete("req-d7", "exc-1", 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("exc-1").andExpect(jsonPath("$.deleted").value(false));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        create("req-i1", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        create("req-i1", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("exc-1")).isEqualTo(1);

        merge("req-i2", "exc-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12").andExpect(status().isOk());
        merge("req-i2", "exc-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("exc-1")).isEqualTo(2);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        create("req-i3", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 读数按值比较：11.0 与 11.00 同值，不算异参
        create("req-i3", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11.0")
                .andExpect(status().isCreated());
        // 改读数值：异参 409
        create("req-i3", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isConflict());
        // 改操作者：异参 409
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-i3");
        body.put("operator", "other");
        body.put("observationId", "exc-1");
        body.put("stationId", "ST-A");
        body.put("minuteUtc", "2026-10-07T10:00:00Z");
        body.put("reading", "11");
        mockMvc.perform(post("/api/excursions/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
        assertThat(versionCount("exc-1")).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        create("req-f1", "exc-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 基线版本不存在：失败不占键
        merge("req-f2", "exc-1", 9, "ST-A", "2026-10-07T10:00:00Z", "12").andExpect(status().isNotFound());
        merge("req-f2", "exc-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
