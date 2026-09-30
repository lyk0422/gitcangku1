package com.example.starter.frame;

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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 带版本坐标基准转换 API 的主流程、失败分支与状态/幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 * 覆盖：左闭右开版本选择、缺规则/多规则 422 不入库、六位小数 HALF_UP、站点矩形含边界、
 * requestId 重放与改参 409、规则调整不回溯历史结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoordinateFrameApiTest {

    private static final String V1_FROM = "2026-09-01T00:00:00Z";
    private static final String V2_FROM = "2026-09-15T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM coordinate_observation");
        jdbcTemplate.update("DELETE FROM coordinate_frame_rule");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    private ResultActions publishRule(String requestId, String ruleId, String targetDatum,
                                      String offsetX, String offsetY, String effectiveFromUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("ruleId", ruleId);
        body.put("targetDatum", targetDatum);
        body.put("offsetX", offsetX);
        body.put("offsetY", offsetY);
        body.put("effectiveFromUtc", effectiveFromUtc);
        return mockMvc.perform(post("/api/coordinate/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions observe(String requestId, String observationId, String sourceDatum,
                                  String sourceX, String sourceY, String observedAtUtc) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("coordinateObservationId", observationId);
        body.put("sourceDatum", sourceDatum);
        body.put("sourceX", sourceX);
        body.put("sourceY", sourceY);
        body.put("observedAtUtc", observedAtUtc);
        return mockMvc.perform(post("/api/coordinate/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getObservation(String observationId) throws Exception {
        return mockMvc.perform(get("/api/coordinate/observations/{id}", observationId));
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_observation", Integer.class);
        return count == null ? 0 : count;
    }

    private Map<String, Object> observationRow(String observationId) {
        return jdbcTemplate.queryForMap(
                "SELECT target_x, target_y, rule_id, rule_version, review_status, target_datum "
                        + "FROM coordinate_observation WHERE coordinate_observation_id = ?", observationId);
    }

    private void publishChainV1V2() throws Exception {
        publishRule("req-rule-v1", "R", "CGCS2000", "0", "0", V1_FROM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleId").value("R"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.effectiveFromUtc").value(V1_FROM))
                .andExpect(jsonPath("$.effectiveToUtc").doesNotExist());
        publishRule("req-rule-v2", "R", "CGCS2000-2", "0.5", "-0.5", V2_FROM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 规则版本链 ----------

    @Test
    void publishClosesPreviousOpenVersionAndKeepsChainOrdered() throws Exception {
        publishChainV1V2();

        mockMvc.perform(get("/api/coordinate/rules/{ruleId}", "R"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].effectiveFromUtc").value(V1_FROM))
                // 左闭右开：V1 结束时刻等于 V2 起始时刻
                .andExpect(jsonPath("$[0].effectiveToUtc").value(V2_FROM))
                .andExpect(jsonPath("$[1].version").value(2))
                .andExpect(jsonPath("$[1].effectiveFromUtc").value(V2_FROM))
                .andExpect(jsonPath("$[1].effectiveToUtc").doesNotExist())
                .andExpect(jsonPath("$[1].offsetX").value(0.5))
                .andExpect(jsonPath("$[1].offsetY").value(-0.5));
    }

    @Test
    void publishVersionNotAfterLatestReturns409() throws Exception {
        publishChainV1V2();
        // 新版本生效起始时刻不晚于最新版本起始时刻：拒绝，规则链不变
        publishRule("req-rule-bad", "R", "CGCS2000-3", "1", "1", V2_FROM)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_frame_rule WHERE rule_id = 'R'", Integer.class);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void publishRuleSameRequestIdReplaysAndDifferentParamsReturn409() throws Exception {
        publishRule("req-rule-idem", "R", "CGCS2000", "0.1", "0.2", V1_FROM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        publishRule("req-rule-idem", "R", "CGCS2000", "0.1", "0.2", V1_FROM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        publishRule("req-rule-idem", "R", "CGCS2000", "0.9", "0.2", V1_FROM)
                .andExpect(status().isConflict());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_frame_rule WHERE rule_id = 'R'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    // ---------- 主流程：版本选择与边界状态 ----------

    @Test
    void observeUsesV1BeforeV2StartAndPersistsFullResult() throws Exception {
        publishChainV1V2();
        observe("req-obs-v1", "O-V1", "WGS84", "5", "20", "2026-09-10T08:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coordinateObservationId").value("O-V1"))
                .andExpect(jsonPath("$.sourceDatum").value("WGS84"))
                .andExpect(jsonPath("$.sourceX").value(5))
                .andExpect(jsonPath("$.sourceY").value(20))
                .andExpect(jsonPath("$.observedAtUtc").value("2026-09-10T08:00:00Z"))
                .andExpect(jsonPath("$.targetDatum").value("CGCS2000"))
                .andExpect(jsonPath("$.targetX").value(5.0))
                .andExpect(jsonPath("$.targetY").value(20.0))
                .andExpect(jsonPath("$.ruleId").value("R"))
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));

        // 查询同时给出原坐标、目标坐标、规则版本与复核状态
        getObservation("O-V1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceX").value(5))
                .andExpect(jsonPath("$.targetX").value(5.0))
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));

        Map<String, Object> row = observationRow("O-V1");
        assertThat(row.get("review_status")).isEqualTo("AUTO_MERGED");
        assertThat(row.get("rule_version")).isEqualTo(1);
        assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo("5.000000");
        assertThat((BigDecimal) row.get("target_y")).isEqualByComparingTo("20.000000");
    }

    @Test
    void observeAtV2BoundaryUsesV2BecauseIntervalsAreLeftClosedRightOpen() throws Exception {
        publishChainV1V2();
        // 恰好等于 V2 起始时刻：V1 区间右开不含该点，命中 V2
        observe("req-obs-edge", "O-EDGE", "WGS84", "0", "0", V2_FROM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(0.5))
                .andExpect(jsonPath("$.targetY").value(-0.5))
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        // 早一秒仍属 V1
        observe("req-obs-before", "O-BEFORE", "WGS84", "0", "0", "2026-09-14T23:59:59Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(0.0))
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));
    }

    @Test
    void scenarioObservationAtTenTwentyWithV2OffsetBecomesPendingReview() throws Exception {
        // 题干指定场景：站点矩形 X 0~10、Y 0~30；O 原坐标 (10,20) 经 V2 (+0.5,-0.5)
        // 得 (10.5,19.5)：X 越界，保存为待复核，不自动合并。
        publishChainV1V2();
        observe("req-obs-O", "O", "WGS84", "10", "20", "2026-09-20T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(10.5))
                .andExpect(jsonPath("$.targetY").value(19.5))
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));

        Map<String, Object> row = observationRow("O");
        assertThat(row.get("review_status")).isEqualTo("PENDING_REVIEW");
        assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo("10.500000");
        assertThat((BigDecimal) row.get("target_y")).isEqualByComparingTo("19.500000");
        assertThat(row.get("rule_version")).isEqualTo(2);
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void stationBoundaryPointsAreInsideAndJustOutsideIsPendingReview() throws Exception {
        publishRule("req-rule-zero", "Z", "CGCS2000", "0", "0", V1_FROM).andExpect(status().isCreated());

        // 四个边界点都视为站内，自动合并
        observe("req-b1", "B1", "WGS84", "0", "0", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));
        observe("req-b2", "B2", "WGS84", "10", "30", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));
        observe("req-b3", "B3", "WGS84", "10", "0", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));
        observe("req-b4", "B4", "WGS84", "0", "30", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));

        // 任一方向越过边界即待复核（观测仍有效保留）
        observe("req-b5", "B5", "WGS84", "10.000001", "0", "2026-09-10T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        observe("req-b6", "B6", "WGS84", "0", "30.000001", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        observe("req-b7", "B7", "WGS84", "-0.000001", "0", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        observe("req-b8", "B8", "WGS84", "0", "-0.000001", "2026-09-10T00:00:00Z")
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        assertThat(observationCount()).isEqualTo(8);
    }

    @Test
    void targetCoordinatesRoundedToSixDecimalsHalfUp() throws Exception {
        // 0.0000005 与 -0.0000005 落在第七位小数的 tie：HALF_UP 向绝对值增大方向进位
        publishRule("req-rule-round", "D", "CGCS2000", "0.0000005", "-0.0000005", V1_FROM)
                .andExpect(status().isCreated());
        observe("req-obs-round", "ROUND", "WGS84", "10", "0", "2026-09-10T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(10.000001))
                // 19.999999|5 进位为 20.000000 等价场景：0 + (-0.0000005) HALF_UP 为 -0.000001
                .andExpect(jsonPath("$.targetY").value(-0.000001))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));

        Map<String, Object> row = observationRow("ROUND");
        assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo("10.000001");
        assertThat((BigDecimal) row.get("target_y")).isEqualByComparingTo("-0.000001");
    }

    // ---------- 失败分支：422 / 409 / 404 / 400 ----------

    @Test
    void observeWithoutAnyRuleReturns422AndWritesNothing() throws Exception {
        observe("req-no-rule", "N1", "WGS84", "5", "5", "2026-08-01T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no effective")));
        assertThat(observationCount()).isZero();
        getObservation("N1").andExpect(status().isNotFound());
    }

    @Test
    void observeWhenMultipleRulesEffectiveReturns422AndWritesNothing() throws Exception {
        publishRule("req-rule-a", "A", "DATUM-A", "1", "1", V1_FROM).andExpect(status().isCreated());
        publishRule("req-rule-b", "B", "DATUM-B", "2", "2", V1_FROM).andExpect(status().isCreated());
        observe("req-multi", "M1", "WGS84", "5", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("multiple effective")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("A@V1")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("B@V1")));
        assertThat(observationCount()).isZero();
        // 规则本身是有效配置，仍可查询；只是该时刻无法唯一裁决
        mockMvc.perform(get("/api/coordinate/rules/{ruleId}", "A")).andExpect(status().isOk());
    }

    @Test
    void duplicateObservationIdReturns409AndWritesNothing() throws Exception {
        publishRule("req-rule-dup", "DUP", "CGCS2000", "0", "0", V1_FROM).andExpect(status().isCreated());
        observe("req-dup-1", "DUP-O", "WGS84", "1", "1", "2026-09-10T00:00:00Z")
                .andExpect(status().isCreated());
        observe("req-dup-2", "DUP-O", "WGS84", "2", "2", "2026-09-10T00:00:00Z")
                .andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
        Map<String, Object> row = observationRow("DUP-O");
        assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo("1.000000");
    }

    @Test
    void invalidPayloadReturns400() throws Exception {
        // 坐标不是十进制
        observe("req-bad-num", "BAD", "WGS84", "x", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isBadRequest());
        // 时刻不是带 Z 的 ISO-8601 UTC
        observe("req-bad-time", "BAD", "WGS84", "5", "5", "2026-09-10 08:00:00")
                .andExpect(status().isBadRequest());
        assertThat(observationCount()).isZero();
    }

    @Test
    void getUnknownRuleReturns404() throws Exception {
        mockMvc.perform(get("/api/coordinate/rules/{ruleId}", "NONE"))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等与历史固化 ----------

    @Test
    void sameRequestIdReplaysFirstCoordinatesAndStatus() throws Exception {
        publishChainV1V2();
        observe("req-obs-replay", "RP", "WGS84", "10", "20", "2026-09-20T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(10.5))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        // 同键同参重放：首次坐标与状态原样返回，不产生第二条记录
        observe("req-obs-replay", "RP", "WGS84", "10", "20", "2026-09-20T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(10.5))
                .andExpect(jsonPath("$.targetY").value(19.5))
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithChangedDatumOrCoordinatesReturns409() throws Exception {
        publishChainV1V2();
        observe("req-obs-change", "CG", "WGS84", "5", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isCreated());
        // 修改原基准
        observe("req-obs-change", "CG", "GCJ02", "5", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isConflict());
        // 修改原坐标
        observe("req-obs-change", "CG", "WGS84", "6", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isConflict());
        observe("req-obs-change", "CG", "WGS84", "5", "6", "2026-09-10T00:00:00Z")
                .andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
        Map<String, Object> row = observationRow("CG");
        assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo("5.000000");
        assertThat(row.get("review_status")).isEqualTo("AUTO_MERGED");
    }

    @Test
    void failed422RequestDoesNotOccupyRequestId() throws Exception {
        // 先无规则：422 失败不占键
        observe("req-obs-retry", "RT", "WGS84", "5", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isUnprocessableEntity());
        // 发布覆盖该时刻的规则后，同一 requestId 带相同参数应成功
        publishRule("req-rule-rt", "RT-R", "CGCS2000", "0", "0", V1_FROM).andExpect(status().isCreated());
        observe("req-obs-retry", "RT", "WGS84", "5", "5", "2026-09-10T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void laterRuleChangesDoNotReinterpretHistoricalObservation() throws Exception {
        publishRule("req-rule-h1", "H", "CGCS2000", "0", "0", V1_FROM).andExpect(status().isCreated());
        observe("req-obs-h", "H-O", "WGS84", "7", "21", "2026-09-10T00:00:00Z")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.targetX").value(7.0))
                .andExpect(jsonPath("$.targetY").value(21.0))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));

        // 之后发布偏移巨大的 V3：历史观测的目标坐标、规则版本与复核状态均保持不变
        publishRule("req-rule-h3", "H", "CGCS2000-N", "100", "100", "2026-10-01T00:00:00Z")
                .andExpect(status().isCreated());
        getObservation("H-O")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.targetX").value(7.0))
                .andExpect(jsonPath("$.targetY").value(21.0))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));
        Map<String, Object> row = observationRow("H-O");
        assertThat(row.get("rule_version")).isEqualTo(1);
        assertThat((BigDecimal) row.get("target_x")).isEqualByComparingTo("7.000000");
    }
}
