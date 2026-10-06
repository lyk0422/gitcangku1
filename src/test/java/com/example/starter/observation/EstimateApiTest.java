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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分层样方密度估计成果 API 的主流程、冻结语义与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EstimateApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM density_estimate_member");
        jdbcTemplate.update("DELETE FROM density_estimate_layer");
        jdbcTemplate.update("DELETE FROM density_estimate");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObs(String requestId, String obsId,
                                    Integer count, Integer area) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", obsId);
        body.put("location", "样方");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("individualCount", count);
        }
        if (area != null) {
            body.put("sampledArea", area);
        }
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeObs(String requestId, String obsId, int baseVersion,
                                   Integer count, Integer area) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "样方");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("individualCount", count);
        }
        if (area != null) {
            body.put("sampledArea", area);
        }
        return mockMvc.perform(post("/api/observations/{id}/merge", obsId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteObs(String requestId, String obsId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", obsId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> member(String obsId, int version) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("observationId", obsId);
        member.put("version", version);
        return member;
    }

    private Map<String, Object> layer(int totalArea, Map<String, Object>... members) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("totalArea", totalArea);
        layer.put("members", List.of(members));
        return layer;
    }

    private ResultActions estimate(String requestId, String estimateId, String operator,
                                   List<Map<String, Object>> layers) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("estimateId", estimateId);
        body.put("operator", operator);
        body.put("layers", layers);
        return mockMvc.perform(post("/api/estimates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getEstimate(String estimateId) throws Exception {
        return mockMvc.perform(get("/api/estimates/{id}", estimateId));
    }

    private int estimateCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM density_estimate", Integer.class);
        return count == null ? 0 : count;
    }

    private int requestLogCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    // ---------- 主流程 ----------

    @Test
    void estimateComputesExactTotalWithHalfUpDisplay() throws Exception {
        // 题干合成场景：两层总体面积 100/200，样本计数 1 面积 3、计数 2 面积 3
        createObs("req-o1", "obs-1", 1, 3).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());

        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimateId").value("est-1"))
                .andExpect(jsonPath("$.requestId").value("req-e1"))
                .andExpect(jsonPath("$.operator").value("operator-a"))
                .andExpect(jsonPath("$.layerCount").value(2))
                .andExpect(jsonPath("$.observationCount").value(2))
                // 精确总量 500/3：展示 166.667 而非 166.6；若用舍入密度计算会得到 166.700
                .andExpect(jsonPath("$.resultExact").value("500/3"))
                .andExpect(jsonPath("$.resultDisplay").value("166.667"))
                .andExpect(jsonPath("$.layers[0].totalArea").value(100))
                .andExpect(jsonPath("$.layers[0].densityExact").value("1/3"))
                .andExpect(jsonPath("$.layers[0].members[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.layers[0].members[0].version").value(1))
                .andExpect(jsonPath("$.layers[0].members[0].individualCount").value(1))
                .andExpect(jsonPath("$.layers[0].members[0].sampledArea").value(3))
                .andExpect(jsonPath("$.layers[1].totalArea").value(200))
                .andExpect(jsonPath("$.layers[1].densityExact").value("2/3"));

        // 精确结果以分子/分母冻结落库，层与成员明细完整
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result_numerator FROM density_estimate WHERE estimate_id = 'est-1'",
                String.class)).isEqualTo("500");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result_denominator FROM density_estimate WHERE estimate_id = 'est-1'",
                String.class)).isEqualTo("3");
        Integer layerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM density_estimate_layer WHERE estimate_id = 'est-1'", Integer.class);
        Integer memberRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM density_estimate_member WHERE estimate_id = 'est-1'", Integer.class);
        assertThat(layerRows).isEqualTo(2);
        assertThat(memberRows).isEqualTo(2);
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void estimateIsFrozenAgainstLaterObservationChanges() throws Exception {
        createObs("req-o1", "obs-1", 1, 3).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());
        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isCreated());

        // 成果生成后修改观测：计数变更、版本前进到 2
        mergeObs("req-m1", "obs-1", 1, 9, 3).andExpect(status().isOk());

        // 已冻结成果不倒改：成员版本、计数与精确结果保持原样
        getEstimate("est-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultExact").value("500/3"))
                .andExpect(jsonPath("$.resultDisplay").value("166.667"))
                .andExpect(jsonPath("$.layers[0].members[0].version").value(1))
                .andExpect(jsonPath("$.layers[0].members[0].individualCount").value(1));

        // 再用旧版本生成新成果：409 并携带当前版本
        estimate("req-e2", "est-2", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(estimateCount()).isEqualTo(1);
    }

    // ---------- 失败分支：整次不生成成果，失败不占键 ----------

    @Test
    void sampledAreaExceedingLayerTotalReturns422AndFailureDoesNotOccupyKey() throws Exception {
        createObs("req-o1", "obs-1", 5, 10).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 1, 1).andExpect(status().isCreated());

        // 层采样面积总和 10 大于该层总体面积 5：422，整次不生成成果
        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(5, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
        assertThat(requestLogCount("req-e1")).isZero();

        // 同一 requestId 修正参数后可正常执行：失败不占键
        // 总量 = (5/10)×100 + (1/1)×200 = 250
        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultExact").value("250/1"))
                .andExpect(jsonPath("$.resultDisplay").value("250.000"));
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void missingCountAreaGroupReturns422() throws Exception {
        // obs-1 未录入计数面积组
        createObs("req-o1", "obs-1", null, null).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());

        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
        assertThat(requestLogCount("req-e1")).isZero();
    }

    @Test
    void tombstoneObservationReturns422() throws Exception {
        createObs("req-o1", "obs-1", 1, 3).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());
        deleteObs("req-d1", "obs-1", 1).andExpect(status().isOk());

        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 2)), layer(200, member("obs-2", 1))))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
        assertThat(requestLogCount("req-e1")).isZero();
    }

    @Test
    void staleSourceVersionReturns409() throws Exception {
        createObs("req-o1", "obs-1", 1, 3).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());
        // obs-1 前进到版本 2，请求仍引用旧版本 1
        mergeObs("req-m1", "obs-1", 1, 5, 3).andExpect(status().isOk());

        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(estimateCount()).isZero();
        assertThat(requestLogCount("req-e1")).isZero();
    }

    @Test
    void missingObservationReturns404() throws Exception {
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());
        estimate("req-e1", "est-1", "operator-a",
                List.of(layer(100, member("obs-x", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isNotFound());
        assertThat(estimateCount()).isZero();
    }

    @Test
    void getMissingEstimateReturns404() throws Exception {
        getEstimate("est-x").andExpect(status().isNotFound());
    }

    // ---------- 结构校验 400 ----------

    @Test
    void structuralViolationsReturn400() throws Exception {
        createObs("req-o1", "obs-1", 1, 3).andExpect(status().isCreated());
        createObs("req-o2", "obs-2", 2, 3).andExpect(status().isCreated());

        // 层数不足 2
        estimate("req-s1", "est-1", "operator-a", List.of(layer(100, member("obs-1", 1))))
                .andExpect(status().isBadRequest());
        // 层数超过 5
        estimate("req-s2", "est-1", "operator-a", List.of(
                layer(100, member("obs-1", 1)), layer(100, member("obs-2", 1)),
                layer(100, member("obs-3", 1)), layer(100, member("obs-4", 1)),
                layer(100, member("obs-5", 1)), layer(100, member("obs-6", 1))))
                .andExpect(status().isBadRequest());
        // 层内无成员
        estimate("req-s3", "est-1", "operator-a",
                List.of(layer(100), layer(200, member("obs-2", 1))))
                .andExpect(status().isBadRequest());
        // 层内成员超过 5
        estimate("req-s4", "est-1", "operator-a", List.of(
                layer(100, member("a1", 1), member("a2", 1), member("a3", 1),
                        member("a4", 1), member("a5", 1), member("a6", 1)),
                layer(200, member("obs-2", 1))))
                .andExpect(status().isBadRequest());
        // 观测跨层重复
        estimate("req-s5", "est-1", "operator-a",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-1", 1))))
                .andExpect(status().isBadRequest());
        // 非正总体面积
        estimate("req-s6", "est-1", "operator-a",
                List.of(layer(0, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isBadRequest());
        // 空操作者
        estimate("req-s7", "est-1", " ",
                List.of(layer(100, member("obs-1", 1)), layer(200, member("obs-2", 1))))
                .andExpect(status().isBadRequest());

        assertThat(estimateCount()).isZero();
        // 失败的结构校验请求均不占键
        for (String requestId : List.of("req-s1", "req-s2", "req-s3", "req-s4", "req-s5", "req-s6", "req-s7")) {
            assertThat(requestLogCount(requestId)).isZero();
        }
    }

    @Test
    void moreThanTwentyObservationsReturns400() throws Exception {
        // 5 层 × 5 个不同观测 = 25 个观测，超过总量 20 上限；结构校验先于观测存在性检查
        List<Map<String, Object>> layers = new java.util.ArrayList<>();
        for (int layerIndex = 0; layerIndex < 5; layerIndex++) {
            Map<String, Object> layer = new LinkedHashMap<>();
            layer.put("totalArea", 100);
            List<Map<String, Object>> members = new java.util.ArrayList<>();
            for (int memberIndex = 0; memberIndex < 5; memberIndex++) {
                members.add(member("obs-" + layerIndex + "-" + memberIndex, 1));
            }
            layer.put("members", members);
            layers.add(layer);
        }
        estimate("req-t1", "est-1", "operator-a", layers)
                .andExpect(status().isBadRequest());
        assertThat(estimateCount()).isZero();
        assertThat(requestLogCount()).isZero();
    }
}
