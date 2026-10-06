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
 * 分层样方密度估计 API 测试：精确计算与展示、源约束（422/409/404）、结构校验与幂等边界。
 * 真实 H2 内存库（MySQL 兼容模式），每用例前清空全部相关表。
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
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObs(String requestId, String id, Integer count, Integer sampleArea)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", id);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("count", count);
        }
        if (sampleArea != null) {
            body.put("sampleArea", sampleArea);
        }
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeObs(String requestId, String id, int baseVersion, String location,
                                   Integer count, Integer sampleArea) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("count", count);
        }
        if (sampleArea != null) {
            body.put("sampleArea", sampleArea);
        }
        return mockMvc.perform(post("/api/observations/{id}/merge", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> member(String observationId, int expectedVersion) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("observationId", observationId);
        member.put("expectedVersion", expectedVersion);
        return member;
    }

    private Map<String, Object> layer(int totalArea, Map<String, Object>... members) {
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("totalArea", totalArea);
        layer.put("members", List.of(members));
        return layer;
    }

    private ResultActions estimate(String requestId, String estimateId, String operator,
                                   Map<String, Object>... layers) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("estimateId", estimateId);
        body.put("operator", operator);
        body.put("layers", List.of(layers));
        return mockMvc.perform(post("/api/estimates")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long estimateCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM density_estimate", Long.class);
        return count == null ? 0 : count;
    }

    private long requestLogCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Long.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：精确计算与展示 ----------

    @Test
    void estimateComputesExactTotalAndDisplaysHalfUp() throws Exception {
        // 题干合成样例：两层总体面积 100/200，样本计数 1 面积 3、计数 2 面积 3
        createObs("req-e1-c1", "obs-a", 1, 3).andExpect(status().isCreated());
        createObs("req-e1-c2", "obs-b", 2, 3).andExpect(status().isCreated());

        estimate("req-e1", "est-1", "operator-a",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estimateId").value("est-1"))
                .andExpect(jsonPath("$.operator").value("operator-a"))
                .andExpect(jsonPath("$.layerCount").value(2))
                .andExpect(jsonPath("$.layers[0].totalArea").value(100))
                .andExpect(jsonPath("$.layers[0].countSum").value(1))
                .andExpect(jsonPath("$.layers[0].sampleAreaSum").value(3))
                .andExpect(jsonPath("$.layers[0].densityExact").value("1/3"))
                .andExpect(jsonPath("$.layers[0].density").value("0.333"))
                .andExpect(jsonPath("$.layers[0].members[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.layers[0].members[0].version").value(1))
                .andExpect(jsonPath("$.layers[0].members[0].count").value(1))
                .andExpect(jsonPath("$.layers[0].members[0].sampleArea").value(3))
                .andExpect(jsonPath("$.layers[1].densityExact").value("2/3"))
                .andExpect(jsonPath("$.layers[1].density").value("0.667"))
                // 精确总量 500/3：展示 166.667 而非 166.6，也不是舍入密度回算的 166.700
                .andExpect(jsonPath("$.totalExact").value("500/3"))
                .andExpect(jsonPath("$.total").value("166.667"));

        // 精确有理数落库
        String numerator = jdbcTemplate.queryForObject(
                "SELECT total_numerator FROM density_estimate WHERE estimate_id = 'est-1'", String.class);
        String denominator = jdbcTemplate.queryForObject(
                "SELECT total_denominator FROM density_estimate WHERE estimate_id = 'est-1'", String.class);
        String display = jdbcTemplate.queryForObject(
                "SELECT total_display FROM density_estimate WHERE estimate_id = 'est-1'", String.class);
        assertThat(numerator).isEqualTo("500");
        assertThat(denominator).isEqualTo("3");
        assertThat(display).isEqualTo("166.667");

        // 查询接口返回同一冻结成果
        mockMvc.perform(get("/api/estimates/{id}", "est-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("166.667"))
                .andExpect(jsonPath("$.totalExact").value("500/3"));
    }

    @Test
    void getMissingEstimateReturns404() throws Exception {
        mockMvc.perform(get("/api/estimates/{id}", "est-x")).andExpect(status().isNotFound());
    }

    // ---------- 源约束：422 / 409 / 404 ----------

    @Test
    void sampleAreaExceedsLayerTotalReturns422() throws Exception {
        createObs("req-s1-c1", "obs-a", 4, 5).andExpect(status().isCreated());
        createObs("req-s1-c2", "obs-b", 1, 1).andExpect(status().isCreated());

        // 层 1 采样面积总和 5 > 总体面积 4：422，整次不生成成果
        long requestLogBefore = requestLogCount();
        estimate("req-s1", "est-s1", "operator-a",
                layer(4, member("obs-a", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
        // 失败不占键：去重记录不增加
        assertThat(requestLogCount()).isEqualTo(requestLogBefore);

        // 失败不占键：修正参数后同一 requestId 可正常生成（4/5×6 + 1×10 = 74/5 = 14.8）
        estimate("req-s1", "est-s1", "operator-a",
                layer(6, member("obs-a", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalExact").value("74/5"))
                .andExpect(jsonPath("$.total").value("14.800"));
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void missingCountAreaFieldsReturns422() throws Exception {
        createObs("req-f1-c1", "obs-a", null, null).andExpect(status().isCreated());
        createObs("req-f1-c2", "obs-b", 1, 1).andExpect(status().isCreated());

        estimate("req-f1", "est-f1", "operator-a",
                layer(10, member("obs-a", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
    }

    @Test
    void tombstoneObservationReturns422() throws Exception {
        createObs("req-t1-c1", "obs-a", 1, 2).andExpect(status().isCreated());
        createObs("req-t1-c2", "obs-b", 1, 1).andExpect(status().isCreated());
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-t1-d\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());

        estimate("req-t1", "est-t1", "operator-a",
                layer(10, member("obs-a", 2)), layer(10, member("obs-b", 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
    }

    @Test
    void unresolvedConflictReturns422AndClearsAfterSuccessfulMerge() throws Exception {
        createObs("req-u1-c1", "obs-a", 1, 2).andExpect(status().isCreated());
        createObs("req-u1-c2", "obs-b", 1, 1).andExpect(status().isCreated());
        // 服务端前进到 v2；离线端基于 v1 改同一地点字段：合并冲突，落未解决冲突标记
        mergeObs("req-u1-m1", "obs-a", 1, "站点B", 1, 2).andExpect(status().isOk());
        mergeObs("req-u1-m2", "obs-a", 1, "站点C", 1, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"));

        estimate("req-u1", "est-u1", "operator-a",
                layer(10, member("obs-a", 2)), layer(10, member("obs-b", 1)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();

        // 成功的合并（与当前一致，不加版本）清除冲突标记后，同一 requestId 可正常生成
        mergeObs("req-u1-m3", "obs-a", 2, "站点B", 1, 2).andExpect(status().isOk());
        estimate("req-u1", "est-u1", "operator-a",
                layer(10, member("obs-a", 2)), layer(10, member("obs-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value("15.000"));
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void staleVersionReturns409() throws Exception {
        createObs("req-v1-c1", "obs-a", 1, 2).andExpect(status().isCreated());
        createObs("req-v1-c2", "obs-b", 1, 1).andExpect(status().isCreated());
        mergeObs("req-v1-m1", "obs-a", 1, "站点B", 1, 2).andExpect(status().isOk());

        // obs-a 当前已是 v2，期望 v1：旧版本 409
        estimate("req-v1", "est-v1", "operator-a",
                layer(10, member("obs-a", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(estimateCount()).isZero();
    }

    @Test
    void missingObservationReturns404() throws Exception {
        createObs("req-n1-c1", "obs-b", 1, 1).andExpect(status().isCreated());
        estimate("req-n1", "est-n1", "operator-a",
                layer(10, member("obs-x", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isNotFound());
        assertThat(estimateCount()).isZero();
    }

    @Test
    void invalidEstimateLeavesNoPartialResult() throws Exception {
        createObs("req-a1-c1", "obs-a", 1, 2).andExpect(status().isCreated());
        createObs("req-a1-c2", "obs-b", 1, 1).andExpect(status().isCreated());

        // 第一层合法、第二层成员为墓碑：整次失败，不生成任何成果与去重记录
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-a1-d\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        long requestLogBefore = requestLogCount();
        estimate("req-a1", "est-a1", "operator-a",
                layer(10, member("obs-a", 1)), layer(10, member("obs-b", 2)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(estimateCount()).isZero();
        // 失败不占键：去重记录不增加
        assertThat(requestLogCount()).isEqualTo(requestLogBefore);
        // 合法层的源观测不受影响
        mockMvc.perform(get("/api/observations/{id}", "obs-a"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false));
    }

    // ---------- 结构校验：400 ----------

    @Test
    void structuralViolationsReturn400() throws Exception {
        // 层数不足 2
        estimate("req-b1", "est-b1", "operator-a", layer(10, member("obs-a", 1)))
                .andExpect(status().isBadRequest());
        // 层数超过 5
        estimate("req-b2", "est-b2", "operator-a",
                layer(10, member("obs-a", 1)), layer(10, member("obs-b", 1)),
                layer(10, member("obs-c", 1)), layer(10, member("obs-d", 1)),
                layer(10, member("obs-e", 1)), layer(10, member("obs-f", 1)))
                .andExpect(status().isBadRequest());
        // 层内观测重复
        estimate("req-b3", "est-b3", "operator-a",
                layer(10, member("obs-a", 1), member("obs-a", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isBadRequest());
        // 观测跨层重复
        estimate("req-b4", "est-b4", "operator-a",
                layer(10, member("obs-a", 1)), layer(10, member("obs-a", 1)))
                .andExpect(status().isBadRequest());
        // 层内成员为空
        estimate("req-b5", "est-b5", "operator-a",
                layer(10), layer(10, member("obs-a", 1)))
                .andExpect(status().isBadRequest());
        // 操作者为空白
        estimate("req-b6", "est-b6", " ",
                layer(10, member("obs-a", 1)), layer(10, member("obs-b", 1)))
                .andExpect(status().isBadRequest());
        // 总计超过 20 个观测（5 层 × 5 成员 = 25）
        Map<String, Object>[] layers = new Map[5];
        for (int i = 0; i < 5; i++) {
            Map<String, Object>[] members = new Map[5];
            for (int j = 0; j < 5; j++) {
                members[j] = member("obs-" + i + "-" + j, 1);
            }
            layers[i] = layer(10, members);
        }
        estimate("req-b7", "est-b7", "operator-a", layers)
                .andExpect(status().isBadRequest());
        // 全部失败均不生成成果、不占键
        assertThat(estimateCount()).isZero();
        assertThat(requestLogCount()).isZero();
    }

    // ---------- 幂等与换序同参 ----------

    @Test
    void sameRequestIdAndParamsReplaysFirstResponse() throws Exception {
        createObs("req-i1-c1", "obs-a", 1, 3).andExpect(status().isCreated());
        createObs("req-i1-c2", "obs-b", 2, 3).andExpect(status().isCreated());

        estimate("req-i1", "est-i1", "operator-a",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated());
        // 同键同参：回首次完整响应（状态与内容一致），不重复生成
        estimate("req-i1", "est-i1", "operator-a",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value("166.667"))
                .andExpect(jsonPath("$.totalExact").value("500/3"));
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createObs("req-i2-c1", "obs-a", 1, 3).andExpect(status().isCreated());
        createObs("req-i2-c2", "obs-b", 2, 3).andExpect(status().isCreated());

        estimate("req-i2", "est-i2", "operator-a",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated());
        estimate("req-i2", "est-i2", "operator-a",
                layer(150, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isConflict());
        estimate("req-i2", "est-i2", "operator-b",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isConflict());
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void layerAndMemberReorderIsSameParams() throws Exception {
        createObs("req-i3-c1", "obs-a", 1, 3).andExpect(status().isCreated());
        createObs("req-i3-c2", "obs-b", 2, 3).andExpect(status().isCreated());
        createObs("req-i3-c3", "obs-c", 3, 3).andExpect(status().isCreated());

        estimate("req-i3", "est-i3", "operator-a",
                layer(100, member("obs-a", 1), member("obs-c", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated());
        // 层换序 + 层内成员换序：同 requestId 视为同参，重放首次响应
        // （(1+3)/(3+3)×100 + 2/3×200 = 200/3 + 400/3 = 200）
        estimate("req-i3", "est-i3", "operator-a",
                layer(200, member("obs-b", 1)), layer(100, member("obs-c", 1), member("obs-a", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value("200.000"));
        // 换序后以新 requestId 携带同一 estimateId：同参重放原成果
        estimate("req-i3-b", "est-i3", "operator-a",
                layer(200, member("obs-b", 1)), layer(100, member("obs-c", 1), member("obs-a", 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("200.000"));
        assertThat(estimateCount()).isEqualTo(1);
    }

    @Test
    void estimateIdReusedWithDifferentParamsReturns409() throws Exception {
        createObs("req-i4-c1", "obs-a", 1, 3).andExpect(status().isCreated());
        createObs("req-i4-c2", "obs-b", 2, 3).andExpect(status().isCreated());

        estimate("req-i4", "est-i4", "operator-a",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated());
        estimate("req-i4-b", "est-i4", "operator-a",
                layer(100, member("obs-a", 1)), layer(300, member("obs-b", 1)))
                .andExpect(status().isConflict());
        assertThat(estimateCount()).isEqualTo(1);
    }

    // ---------- 冻结：后续修改不回改成果 ----------

    @Test
    void estimateFrozenAgainstLaterModification() throws Exception {
        createObs("req-z1-c1", "obs-a", 1, 3).andExpect(status().isCreated());
        createObs("req-z1-c2", "obs-b", 2, 3).andExpect(status().isCreated());

        estimate("req-z1", "est-z1", "operator-a",
                layer(100, member("obs-a", 1)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value("166.667"));

        // 后续修改观测计数：obs-a 前进到 v2，计数 1 → 9
        mergeObs("req-z1-m1", "obs-a", 1, "站点A", 9, 3).andExpect(status().isOk());

        // 已生成成果不倒改：仍冻结 v1 的计数与精确结果
        mockMvc.perform(get("/api/estimates/{id}", "est-z1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layers[0].members[0].version").value(1))
                .andExpect(jsonPath("$.layers[0].members[0].count").value(1))
                .andExpect(jsonPath("$.total").value("166.667"))
                .andExpect(jsonPath("$.totalExact").value("500/3"));

        // 基于新版本可生成新成果：9/3×100 + 2/3×200 = 1300/3
        estimate("req-z2", "est-z2", "operator-a",
                layer(100, member("obs-a", 2)), layer(200, member("obs-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalExact").value("1300/3"))
                .andExpect(jsonPath("$.total").value("433.333"));
        assertThat(estimateCount()).isEqualTo(2);
    }
}