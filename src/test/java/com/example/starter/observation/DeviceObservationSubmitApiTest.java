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
 * 设备观测提交 API 测试：矫正时刻换算、命中边界、胜者排序与幂等（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceObservationSubmitApiTest {

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

    private void registerOffset(String deviceId, String requestId,
                                String effectiveFromUtc, int offsetSeconds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("effectiveFromUtc", effectiveFromUtc);
        body.put("offsetSeconds", offsetSeconds);
        mockMvc.perform(post("/api/devices/{deviceId}/offsets", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private ResultActions submit(String requestId, String observationId, String deviceId,
                                 String deviceLocalTime, String location, String reading, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("deviceId", deviceId);
        body.put("deviceLocalTime", deviceLocalTime);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/device-observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getState(String observationId) throws Exception {
        return mockMvc.perform(get("/api/device-observations/{id}", observationId));
    }

    private ResultActions listVersions(String observationId) throws Exception {
        return mockMvc.perform(get("/api/device-observations/{id}/versions", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_observation WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 矫正换算 ----------

    @Test
    void submitComputesCorrectedTimeAndKeepsRawLocalTime() throws Exception {
        registerOffset("dev-1", "req-s0", "2026-09-25T00:00:00Z", 3600);
        submit("req-s1", "obs-1", "dev-1", "2026-09-25T10:00:00", "站点A", "1.5", "首报")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deviceId").value("dev-1"))
                .andExpect(jsonPath("$.deviceLocalTime").value("2026-09-25T10:00:00"))
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T11:00:00Z"))
                .andExpect(jsonPath("$.mergeSeq").value(1))
                .andExpect(jsonPath("$.currentVersion").value(1));

        // 原始本地时刻与矫正后时刻一并保存
        getState("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.deviceLocalTime").value("2026-09-25T10:00:00"))
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T11:00:00Z"))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.versionCount").value(1));
    }

    @Test
    void submitAppliesNegativeOffset() throws Exception {
        registerOffset("dev-1", "req-s2", "2026-09-25T00:00:00Z", -90);
        submit("req-s3", "obs-1", "dev-1", "2026-09-25T10:00:00", "站点A", "1.0", "负偏移")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T09:58:30Z"));
    }

    @Test
    void submitWithoutCoveringOffsetReturns400AndDoesNotOccupyRequestId() throws Exception {
        registerOffset("dev-1", "req-s4", "2026-09-25T08:00:00Z", 10);
        // 本地时刻早于唯一偏移记录的生效起始：无命中记录
        submit("req-s5", "obs-1", "dev-1", "2026-09-25T07:59:59", "站点A", "1.0", "过早")
                .andExpect(status().isBadRequest());
        // 完全未登记设备
        submit("req-s6", "obs-1", "dev-x", "2026-09-25T10:00:00", "站点A", "1.0", "无设备")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isZero();
        // 失败不占键：同一 requestId 换合法参数后正常执行
        submit("req-s5", "obs-1", "dev-1", "2026-09-25T08:00:00", "站点A", "1.0", "边界命中")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T08:00:10Z"));
    }

    @Test
    void submitMatchesOffsetIntervalBoundaries() throws Exception {
        registerOffset("dev-1", "req-s7", "2026-09-25T00:00:00Z", 1);
        registerOffset("dev-1", "req-s8", "2026-09-25T10:00:00Z", 2);
        // 恰好落在第二条记录生效起始：命中新记录
        submit("req-s9", "obs-1", "dev-1", "2026-09-25T10:00:00", "站点A", "1.0", "边界")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:00:02Z"));
        // 落在两条记录之间：命中前一条
        submit("req-s10", "obs-2", "dev-1", "2026-09-25T09:59:59", "站点A", "1.0", "区间中")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:00:00Z"));
    }

    @Test
    void submitWithInvalidLocalTimeReturns400() throws Exception {
        registerOffset("dev-1", "req-s11", "2026-09-25T00:00:00Z", 1);
        submit("req-s12", "obs-1", "dev-1", "2026-09-25 10:00:00", "站点A", "1.0", "格式错")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isZero();
    }

    // ---------- 胜者排序 ----------

    @Test
    void winnerIsLatestByCorrectedTimeNotArrivalOrder() throws Exception {
        registerOffset("dev-1", "req-w0", "2026-09-25T00:00:00Z", 0);
        // 先到达的版本矫正后时刻更晚
        submit("req-w1", "obs-1", "dev-1", "2026-09-25T10:00:10", "站点A", "1.0", "晚时刻先到")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion").value(1));
        // 后到达的版本矫正后时刻更早：不改变胜者
        submit("req-w2", "obs-1", "dev-1", "2026-09-25T10:00:05", "站点B", "2.0", "早时刻后到")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.currentVersion").value(1));

        getState("obs-1")
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.versionCount").value(2));
        // 版本列表按合并顺序排位：v2 在前，v1 为当前胜者
        listVersions("obs-1")
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].current").value(false))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].current").value(true));
    }

    @Test
    void tieOnCorrectedTimeBreaksByDeviceIdThenObservationId() throws Exception {
        registerOffset("dev-a", "req-t0", "2026-09-25T00:00:00Z", 0);
        registerOffset("dev-b", "req-t1", "2026-09-25T00:00:00Z", 0);
        // 矫正后时刻相同：设备标识字典序大者排位靠后（胜出）
        submit("req-t2", "obs-1", "dev-b", "2026-09-25T10:00:00", "站点B", "1.0", "设备B")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion").value(1));
        submit("req-t3", "obs-1", "dev-a", "2026-09-25T10:00:00", "站点A", "2.0", "设备A")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getState("obs-1")
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.deviceId").value("dev-b"));

        // 全局合并顺序稳定可复现：obs-1 两个版本排位相邻且 obs-2 排在同刻 dev-a 之后
        submit("req-t4", "obs-2", "dev-a", "2026-09-25T10:00:00", "站点C", "3.0", "另一观测")
                .andExpect(status().isCreated());
        listVersions("obs-1")
                .andExpect(jsonPath("$[0].deviceId").value("dev-a"))
                .andExpect(jsonPath("$[0].mergeSeq").value(1))
                .andExpect(jsonPath("$[1].deviceId").value("dev-b"))
                .andExpect(jsonPath("$[1].mergeSeq").value(3));
        getState("obs-2").andExpect(jsonPath("$.mergeSeq").value(2));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalSubmit() throws Exception {
        registerOffset("dev-1", "req-i0", "2026-09-25T00:00:00Z", 60);
        submit("req-i1", "obs-1", "dev-1", "2026-09-25T10:00:00", "站点A", "1.0", "重放")
                .andExpect(status().isCreated());
        submit("req-i1", "obs-1", "dev-1", "2026-09-25T10:00:00", "站点A", "1.0", "重放")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-09-25T10:01:00Z"));
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        registerOffset("dev-1", "req-i2", "2026-09-25T00:00:00Z", 60);
        submit("req-i3", "obs-1", "dev-1", "2026-09-25T10:00:00", "站点A", "1.0", "异参")
                .andExpect(status().isCreated());
        submit("req-i3", "obs-1", "dev-1", "2026-09-25T10:00:01", "站点A", "1.0", "异参")
                .andExpect(status().isConflict());
        submit("req-i3", "obs-2", "dev-1", "2026-09-25T10:00:00", "站点A", "1.0", "异参")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-1")).isEqualTo(1);
        assertThat(versionCount("obs-2")).isZero();
    }
}
