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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分钟观测 API 测试：创建（同站同分钟唯一）、三方合并（时刻字段参与）、删除墓碑与幂等边界。
 * 使用真实 H2 内存库（MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MinuteObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM minute_observation_version");
        jdbcTemplate.update("DELETE FROM minute_observation_current");
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
        return mockMvc.perform(post("/api/minute-observations")
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
        return mockMvc.perform(post("/api/minute-observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/minute-observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/minute-observations/{id}", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM minute_observation_version WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createReturnsVersionOne() throws Exception {
        create("req-mc1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11.5")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("mo-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.stationId").value("ST-A"))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.reading").value("11.5"));
        getCurrent("mo-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value("ST-A"))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:00:00Z"));
        assertThat(versionCount("mo-1")).isEqualTo(1);
    }

    @Test
    void createDuplicateIdReturns409() throws Exception {
        create("req-mc2", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        create("req-mc3", "mo-1", "ST-A", "2026-10-07T10:01:00Z", "12")
                .andExpect(status().isConflict());
        assertThat(versionCount("mo-1")).isEqualTo(1);
    }

    @Test
    void createSameStationMinuteWithDifferentIdReturns409() throws Exception {
        create("req-mc4", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 同站同分钟唯一：不同标识也拒绝
        create("req-mc5", "mo-2", "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isConflict());
        assertThat(versionCount("mo-2")).isZero();
        // 同站不同分钟、不同站同分钟均可
        create("req-mc6", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "12").andExpect(status().isCreated());
        create("req-mc7", "mo-3", "ST-B", "2026-10-07T10:00:00Z", "12").andExpect(status().isCreated());
    }

    @Test
    void createWithInvalidReadingOrMinuteReturns400() throws Exception {
        create("req-mc8", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "1.2345")
                .andExpect(status().isBadRequest());
        create("req-mc9", "mo-1", "ST-A", "2026-10-07T10:00:30Z", "11")
                .andExpect(status().isBadRequest());
        create("req-mc10", "mo-1", "ST-A", "not-a-time", "11")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("mo-1")).isZero();
    }

    @Test
    void createWithBlankOperatorReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-mc11");
        body.put("operator", " ");
        body.put("observationId", "mo-1");
        body.put("stationId", "ST-A");
        body.put("minuteUtc", "2026-10-07T10:00:00Z");
        body.put("reading", "11");
        mockMvc.perform(post("/api/minute-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("mo-1")).isZero();
    }

    // ---------- 三方合并 ----------

    @Test
    void mergeAcceptsCandidateWhenCurrentUnchanged() throws Exception {
        create("req-mm1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        merge("req-mm2", "mo-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12.25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("12.25"));
        assertThat(versionCount("mo-1")).isEqualTo(2);
    }

    @Test
    void mergeMinuteFieldMovesToFreeMinuteAndFreesOldSlot() throws Exception {
        create("req-mm3", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 时刻字段参与三方合并：当前未改（等于基线），接受候选时刻
        merge("req-mm4", "mo-1", 1, "ST-A", "2026-10-07T10:05:00Z", "11")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:05:00Z"));
        // 原时刻槽位已释放
        create("req-mm5", "mo-2", "ST-A", "2026-10-07T10:00:00Z", "9").andExpect(status().isCreated());
    }

    @Test
    void mergeConflictOnMinuteReturns409AndWritesNothing() throws Exception {
        create("req-mm6", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 服务端先把时刻改到 10:05
        merge("req-mm7", "mo-1", 1, "ST-A", "2026-10-07T10:05:00Z", "11").andExpect(status().isOk());
        // 离线端基于版本 1 把时刻改到 10:10：两边都改了时刻，冲突
        merge("req-mm8", "mo-1", 1, "ST-A", "2026-10-07T10:10:00Z", "11")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("minuteUtc"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("mo-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:05:00Z"));
        assertThat(versionCount("mo-1")).isEqualTo(2);
    }

    @Test
    void mergeReadingComparedNumerically() throws Exception {
        create("req-mm9", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11.0").andExpect(status().isCreated());
        // 候选读数 11.00 与基线 11.0 数值相等：视为未改，结果与当前相同，不加版本
        merge("req-mm10", "mo-1", 1, "ST-A", "2026-10-07T10:00:00Z", "11.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("11.0"));
        assertThat(versionCount("mo-1")).isEqualTo(1);
    }

    @Test
    void mergeToOccupiedStationMinuteReturns409() throws Exception {
        create("req-mm11", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        create("req-mm12", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "12").andExpect(status().isCreated());
        // mo-2 合并后落入 mo-1 的同站同分钟槽位：整次 409，不写部分结果
        merge("req-mm13", "mo-2", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isConflict());
        getCurrent("mo-2")
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.minuteUtc").value("2026-10-07T10:01:00Z"));
        assertThat(versionCount("mo-2")).isEqualTo(1);
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deleteCreatesTombstoneAndFreesStationMinuteSlot() throws Exception {
        create("req-md1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        delete("req-md2", "mo-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.stationId").doesNotExist())
                .andExpect(jsonPath("$.minuteUtc").doesNotExist())
                .andExpect(jsonPath("$.reading").doesNotExist());
        getCurrent("mo-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2));
        // 墓碑释放同站同分钟槽位：新记录可复用该时刻
        create("req-md3", "mo-2", "ST-A", "2026-10-07T10:00:00Z", "9").andExpect(status().isCreated());
        assertThat(versionCount("mo-1")).isEqualTo(2);
    }

    @Test
    void deleteWithMismatchedVersionReturns409() throws Exception {
        create("req-md4", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        delete("req-md5", "mo-1", 7)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("mo-1").andExpect(jsonPath("$.deleted").value(false));
    }

    @Test
    void deletedObservationRejectsMergeAndDeleteWith410() throws Exception {
        create("req-md6", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        delete("req-md7", "mo-1", 1).andExpect(status().isOk());
        merge("req-md8", "mo-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isGone());
        delete("req-md9", "mo-1", 2).andExpect(status().isGone());
        getCurrent("mo-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("mo-1")).isEqualTo(2);
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        create("req-mi1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 同键同参重放：读数 11.0 与 11 按数值相等，视为同参，返回原结果
        create("req-mi1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11.0")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("11"));
        assertThat(versionCount("mo-1")).isEqualTo(1);

        merge("req-mi2", "mo-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12").andExpect(status().isOk());
        merge("req-mi2", "mo-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("mo-1")).isEqualTo(2);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        create("req-mi3", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        create("req-mi3", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11")
                .andExpect(status().isConflict());
        create("req-mi3", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isConflict());
        assertThat(versionCount("mo-1")).isEqualTo(1);
        assertThat(versionCount("mo-2")).isZero();
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        create("req-mf1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11").andExpect(status().isCreated());
        // 失败（基线版本不存在）：不占键
        merge("req-mf2", "mo-1", 9, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isNotFound());
        // 同一 requestId 换上合法参数后应正常执行
        merge("req-mf2", "mo-1", 1, "ST-A", "2026-10-07T10:00:00Z", "12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
