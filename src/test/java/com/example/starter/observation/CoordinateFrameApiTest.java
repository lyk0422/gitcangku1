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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 带版本坐标基准转换 API 的主流程、失败分支、幂等与历史冻结测试（真实 H2 内存库，MySQL 兼容模式）。
 * SQL 区间命中（左闭右开）、唯一约束、回滚与写入结果均在 H2 上实际验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CoordinateFrameApiTest {

    private static final Instant V1_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant V2_FROM = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM coordinate_observation");
        jdbcTemplate.update("DELETE FROM coordinate_rule");
    }

    private ResultActions publishRule(int version, String targetFrame, String offsetX, String offsetY,
                                      Instant from, Instant to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleVersion", version);
        body.put("targetFrame", targetFrame);
        body.put("offsetX", new BigDecimal(offsetX));
        body.put("offsetY", new BigDecimal(offsetY));
        body.put("effectiveFromUtc", from);
        if (to != null) {
            body.put("effectiveToUtc", to);
        }
        return mockMvc.perform(post("/api/coordinate/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions ingest(String requestId, String observationId, String sourceFrame,
                                 String x, String y, Instant observedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("sourceFrame", sourceFrame);
        body.put("originalX", new BigDecimal(x));
        body.put("originalY", new BigDecimal(y));
        body.put("observedAtUtc", observedAt);
        return mockMvc.perform(post("/api/coordinate/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int observationCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_observation", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 规则发布 ----------

    @Test
    void publishRuleAndQueryIt() throws Exception {
        publishRule(1, "SITE-LOCAL", "0.10", "-0.20", V1_FROM, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.targetFrame").value("SITE-LOCAL"))
                .andExpect(jsonPath("$.offsetX").value(0.10))
                .andExpect(jsonPath("$.offsetY").value(-0.20))
                .andExpect(jsonPath("$.effectiveFromUtc").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.effectiveToUtc").doesNotExist());

        mockMvc.perform(get("/api/coordinate/rules/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1));
    }

    @Test
    void publishDuplicateRuleVersionReturns409() throws Exception {
        publishRule(1, "FRAME-A", "0", "0", V1_FROM, null).andExpect(status().isCreated());
        publishRule(1, "FRAME-B", "1", "1", V1_FROM, null).andExpect(status().isConflict());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coordinate_rule WHERE rule_version = 1", Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT target_frame FROM coordinate_rule WHERE rule_version = 1", String.class))
                .isEqualTo("FRAME-A");
    }

    @Test
    void publishRuleWithInvalidWindowReturns400() throws Exception {
        publishRule(1, "FRAME-A", "0", "0", V2_FROM, V1_FROM).andExpect(status().isBadRequest());
        publishRule(2, "FRAME-A", "0", "0", V2_FROM, V2_FROM).andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coordinate_rule", Integer.class)).isZero();
    }

    @Test
    void getMissingRuleReturns404() throws Exception {
        mockMvc.perform(get("/api/coordinate/rules/99")).andExpect(status().isNotFound());
    }

    // ---------- 主流程：题目给定场景 ----------

    @Test
    void observationAtBoundaryIsKeptPendingReviewWithoutAutoMerge() throws Exception {
        // V2 自 2026-06-01 UTC 起生效，偏移 (+0.5,-0.5)
        publishRule(2, "SITE-LOCAL", "0.5", "-0.5", V2_FROM, null).andExpect(status().isCreated());

        // 观测 O：原坐标 (10,20)，观测时刻在 V2 区间内
        ingest("req-o1", "obs-O", "FIELD-A", "10", "20", Instant.parse("2026-06-15T08:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("obs-O"))
                .andExpect(jsonPath("$.sourceFrame").value("FIELD-A"))
                .andExpect(jsonPath("$.originalX").value(10.000000))
                .andExpect(jsonPath("$.originalY").value(20.000000))
                .andExpect(jsonPath("$.targetFrame").value("SITE-LOCAL"))
                .andExpect(jsonPath("$.targetX").value(10.500000))
                .andExpect(jsonPath("$.targetY").value(19.500000))
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.autoMerged").value(false));

        mockMvc.perform(get("/api/coordinate/observations/obs-O"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.autoMerged").value(false));
    }

    @Test
    void observationInsideSiteIsAutoMergedIncludingBoundaryPoints() throws Exception {
        publishRule(1, "SITE-LOCAL", "0", "0", V1_FROM, null).andExpect(status().isCreated());

        // 站点矩形边界点视为站内：四角与边中点全部自动合并
        String[][] boundaryPoints = {
                {"0", "0"}, {"10", "0"}, {"0", "30"}, {"10", "30"}, {"5", "30"}, {"10", "15"}
        };
        int index = 0;
        for (String[] point : boundaryPoints) {
            ingest("req-b" + index, "obs-b" + index, "FIELD-A", point[0], point[1],
                    Instant.parse("2026-03-01T00:00:0" + index + "Z"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"))
                    .andExpect(jsonPath("$.autoMerged").value(true));
            index++;
        }

        // 站外任意一侧超出即待复核
        ingest("req-out1", "obs-out1", "FIELD-A", "10.000001", "20", Instant.parse("2026-03-02T00:00:00Z"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.autoMerged").value(false));
        ingest("req-out2", "obs-out2", "FIELD-A", "5", "30.000001", Instant.parse("2026-03-03T00:00:00Z"))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));
    }

    @Test
    void versionSwitchUsesLeftClosedRightOpenWindow() throws Exception {
        // V1 [2026-01-01, 2026-06-01)，V2 [2026-06-01, 开放)
        publishRule(1, "FRAME-1", "0", "0", V1_FROM, V2_FROM).andExpect(status().isCreated());
        publishRule(2, "FRAME-2", "0.5", "-0.5", V2_FROM, null).andExpect(status().isCreated());

        // 恰为左边界 V2_FROM：命中 V2（左闭）
        ingest("req-v1", "obs-v1", "FIELD-A", "10", "20", V2_FROM)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.targetFrame").value("FRAME-2"))
                .andExpect(jsonPath("$.targetX").value(10.5))
                .andExpect(jsonPath("$.targetY").value(19.5));

        // 早一毫秒：命中 V1（右开，V2 尚未生效）
        ingest("req-v2", "obs-v2", "FIELD-A", "10", "20", V2_FROM.minusMillis(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.targetX").value(10.000000))
                .andExpect(jsonPath("$.targetY").value(20.000000));

        // 恰为 V1 的终点：V1 不命中，但 V2 命中
        ingest("req-v3", "obs-v3", "FIELD-A", "1", "1", V1_FROM)
                .andExpect(jsonPath("$.ruleVersion").value(1));
    }

    // ---------- 失败分支：422 且不入库 ----------

    @Test
    void ingestWithoutAnyRuleReturns422AndPersistsNothing() throws Exception {
        // 任何规则都不存在
        ingest("req-n1", "obs-n1", "FIELD-A", "10", "20", Instant.parse("2026-06-15T00:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
        assertThat(observationCount()).isZero();

        publishRule(1, "FRAME-1", "0", "0", V1_FROM, V2_FROM).andExpect(status().isCreated());
        // 观测时刻落在所有区间之外（V1 右开）
        ingest("req-n2", "obs-n2", "FIELD-A", "10", "20", V2_FROM)
                .andExpect(status().isUnprocessableEntity());
        assertThat(observationCount()).isZero();

        // 失败不占键：同一 requestId 改在有规则的时刻提交应成功（携带的观测时刻不同）
        ingest("req-n2", "obs-n2", "FIELD-A", "10", "20", Instant.parse("2026-03-01T00:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1));
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void ingestHittingMultipleRulesReturns422AndPersistsNothing() throws Exception {
        // 两条规则区间在 V2_FROM 之后重叠：该时刻命中多条
        publishRule(1, "FRAME-1", "0", "0", V1_FROM, null).andExpect(status().isCreated());
        publishRule(2, "FRAME-2", "0.5", "-0.5", V2_FROM, null).andExpect(status().isCreated());

        ingest("req-multi", "obs-multi", "FIELD-A", "10", "20", V2_FROM)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
        assertThat(observationCount()).isZero();

        // 仅命中 V1 的时刻不受影响
        ingest("req-single", "obs-single", "FIELD-A", "10", "20", V2_FROM.minusSeconds(1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1));
    }

    @Test
    void getMissingObservationReturns404() throws Exception {
        mockMvc.perform(get("/api/coordinate/observations/obs-x"))
                .andExpect(status().isNotFound());
    }

    // ---------- 幂等与改参 409 ----------

    @Test
    void sameRequestIdReplaysFirstCoordinatesAndStatus() throws Exception {
        publishRule(2, "SITE-LOCAL", "0.5", "-0.5", V2_FROM, null).andExpect(status().isCreated());

        ingest("req-r1", "obs-O", "FIELD-A", "10", "20", Instant.parse("2026-06-15T08:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(10.5))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"));

        // 同键同参重放：首次坐标与状态原样返回（数值等价写法 10.00 也视为同参）
        ingest("req-r1", "obs-O", "FIELD-A", "10.00", "20.000", Instant.parse("2026-06-15T08:00:00Z"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetX").value(10.500000))
                .andExpect(jsonPath("$.targetY").value(19.500000))
                .andExpect(jsonPath("$.ruleVersion").value(2))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.autoMerged").value(false));
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithChangedFrameOrCoordinatesReturns409() throws Exception {
        publishRule(1, "FRAME-1", "0", "0", V1_FROM, null).andExpect(status().isCreated());
        Instant at = Instant.parse("2026-03-01T00:00:00Z");
        ingest("req-r2", "obs-r2", "FIELD-A", "1", "2", at).andExpect(status().isCreated());

        // 修改原基准
        ingest("req-r2", "obs-r2", "FIELD-B", "1", "2", at).andExpect(status().isConflict());
        // 修改原坐标 X
        ingest("req-r2", "obs-r2", "FIELD-A", "1.1", "2", at).andExpect(status().isConflict());
        // 修改原坐标 Y
        ingest("req-r2", "obs-r2", "FIELD-A", "1", "2.1", at).andExpect(status().isConflict());
        // 修改观测时刻
        ingest("req-r2", "obs-r2", "FIELD-A", "1", "2", at.plusSeconds(1)).andExpect(status().isConflict());
        // 复用 requestId 但换 observationId
        ingest("req-r2", "obs-other", "FIELD-A", "1", "2", at).andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void duplicateObservationIdWithDifferentRequestIdReturns409() throws Exception {
        publishRule(1, "FRAME-1", "0", "0", V1_FROM, null).andExpect(status().isCreated());
        Instant at = Instant.parse("2026-03-01T00:00:00Z");
        ingest("req-d1", "obs-dup", "FIELD-A", "1", "2", at).andExpect(status().isCreated());
        ingest("req-d2", "obs-dup", "FIELD-A", "1", "2", at).andExpect(status().isConflict());
        assertThat(observationCount()).isEqualTo(1);
    }

    // ---------- 历史结果冻结 ----------

    @Test
    void laterRuleChangesDoNotReinterpretHistoricalObservations() throws Exception {
        publishRule(1, "FRAME-1", "0", "0", V1_FROM, null).andExpect(status().isCreated());
        Instant at = Instant.parse("2026-03-01T00:00:00Z");
        // 首次观测：站内，V1 零偏移 → (5,20) AUTO_MERGED
        ingest("req-h1", "obs-h1", "FIELD-A", "5", "20", at)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.targetX").value(5.000000))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"));

        // 之后发布 V3（区间不与 V1 重叠，避免多命中）：偏移足以把同一原坐标推出站外
        publishRule(3, "FRAME-3", "6", "0", Instant.parse("2027-01-01T00:00:00Z"), null)
                .andExpect(status().isCreated());

        // 历史观测仍冻结为 V1 的坐标与状态
        mockMvc.perform(get("/api/coordinate/observations/obs-h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.targetFrame").value("FRAME-1"))
                .andExpect(jsonPath("$.targetX").value(5.000000))
                .andExpect(jsonPath("$.targetY").value(20.000000))
                .andExpect(jsonPath("$.reviewStatus").value("AUTO_MERGED"))
                .andExpect(jsonPath("$.autoMerged").value(true));

        Integer ruleVersionInDb = jdbcTemplate.queryForObject(
                "SELECT rule_version FROM coordinate_observation WHERE observation_id = 'obs-h1'", Integer.class);
        assertThat(ruleVersionInDb).isEqualTo(1);
    }
}
