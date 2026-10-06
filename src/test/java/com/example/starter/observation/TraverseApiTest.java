package com.example.starter.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闭合导线成果生成 API 的主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraverseApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM traverse_leg");
        jdbcTemplate.update("DELETE FROM traverse_result");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObservation(String requestId, String observationId,
                                            String dx, String dy, String w) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点" + observationId);
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (dx != null) {
            body.put("dx", dx);
            body.put("dy", dy);
            body.put("w", w);
        }
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions generate(String requestId, String operator, List<List<Object>> legs)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        body.put("legs", legs.stream()
                .map(leg -> Map.of("observationId", leg.get(0), "version", leg.get(1)))
                .toList());
        return mockMvc.perform(post("/api/traverses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<List<Object>> legsOf(Object... idVersionPairs) {
        List<List<Object>> legs = new ArrayList<>();
        for (int i = 0; i < idVersionPairs.length; i += 2) {
            legs.add(List.of(idVersionPairs[i], idVersionPairs[i + 1]));
        }
        return legs;
    }

    /**
     * 按题干合成数据创建三个带位移观测：(1,0)、(0,1)、(-0.94,-1)，权重 100/200/300。
     */
    private void createSyntheticObservations() throws Exception {
        createObservation("req-s1", "obs-t1", "1", "0", "100").andExpect(status().isCreated());
        createObservation("req-s2", "obs-t2", "0", "1", "200").andExpect(status().isCreated());
        createObservation("req-s3", "obs-t3", "-0.94", "-1", "300").andExpect(status().isCreated());
    }

    @Test
    void generateTraverseWithSyntheticDataProducesExactClosure() throws Exception {
        createSyntheticObservations();

        MvcResult result = generate("req-t1", "surveyor-a",
                legsOf("obs-t1", 1, "obs-t2", 1, "obs-t3", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value("req-t1"))
                .andExpect(jsonPath("$.operator").value("surveyor-a"))
                .andExpect(jsonPath("$.legCount").value(3))
                .andExpect(jsonPath("$.closureDx").value("0.06"))
                .andExpect(jsonPath("$.closureDy").value("0"))
                .andExpect(jsonPath("$.totalWeight").value("600"))
                .andExpect(jsonPath("$.legs[0].observationId").value("obs-t1"))
                .andExpect(jsonPath("$.legs[0].adjustedDx").value("0.990000"))
                .andExpect(jsonPath("$.legs[0].adjustedDy").value("0.000000"))
                .andExpect(jsonPath("$.legs[1].adjustedDx").value("-0.020000"))
                .andExpect(jsonPath("$.legs[1].adjustedDy").value("1.000000"))
                .andExpect(jsonPath("$.legs[2].adjustedDx").value("-0.970000"))
                .andExpect(jsonPath("$.legs[2].adjustedDy").value("-1.000000"))
                .andReturn();

        // 修正和精确为 0（从响应原文按数值求和核对）
        JsonNode legs = objectMapper.readTree(result.getResponse().getContentAsString()).get("legs");
        BigDecimal sumDx = BigDecimal.ZERO;
        BigDecimal sumDy = BigDecimal.ZERO;
        for (JsonNode leg : legs) {
            sumDx = sumDx.add(new BigDecimal(leg.get("adjustedDx").asText()));
            sumDy = sumDy.add(new BigDecimal(leg.get("adjustedDy").asText()));
        }
        assertThat(sumDx).isEqualByComparingTo("0");
        assertThat(sumDy).isEqualByComparingTo("0");

        // 成果已落库，可按成果标识查询到相同内容
        String traverseId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("traverseId").asText();
        mockMvc.perform(get("/api/traverses/{id}", traverseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closureDx").value("0.06"))
                .andExpect(jsonPath("$.legs[2].adjustedDx").value("-0.970000"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_leg WHERE traverse_id = ?", Integer.class, traverseId))
                .isEqualTo(3);
    }

    @Test
    void closureErrorBeyondLimitReturns422AndWritesNothing() throws Exception {
        createObservation("req-s1", "obs-t1", "1", "0", "100").andExpect(status().isCreated());
        createObservation("req-s2", "obs-t2", "0", "1", "200").andExpect(status().isCreated());
        // 题干拒绝用例：末边 (-0.899,-1)，闭合差 (0.101, 0) 越限
        createObservation("req-s3", "obs-t3", "-0.899", "-1", "300").andExpect(status().isCreated());

        generate("req-t2", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1, "obs-t3", 1))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traverse_result", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-t2'", Integer.class)).isZero();
    }

    @Test
    void missingDisplacementReturns422() throws Exception {
        createSyntheticObservations();
        // 普通观测（无位移）参与导线：422
        createObservation("req-s4", "obs-t4", null, null, null).andExpect(status().isCreated());

        generate("req-t3", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1, "obs-t4", 1))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void tombstoneReferenceReturns422() throws Exception {
        createSyntheticObservations();
        Map<String, Object> deleteBody = Map.of("requestId", "req-d1", "expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-t3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        generate("req-t4", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1, "obs-t3", 2))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void nonCurrentVersionReturns409() throws Exception {
        createSyntheticObservations();
        // 合并使 obs-t1 前进到版本 2，导线仍引用版本 1
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-m1");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点obs-t1");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "改动备注");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        generate("req-t5", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1, "obs-t3", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    void unknownObservationReturns404() throws Exception {
        createSyntheticObservations();
        generate("req-t6", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1, "obs-missing", 1))
                .andExpect(status().isNotFound());
    }

    @Test
    void duplicatedObservationReturns400() throws Exception {
        createSyntheticObservations();
        generate("req-t7", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1, "obs-t1", 1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void legCountOutOfRangeReturns400() throws Exception {
        createSyntheticObservations();
        generate("req-t8", "surveyor-a", legsOf("obs-t1", 1, "obs-t2", 1))
                .andExpect(status().isBadRequest());
        List<List<Object>> elevenLegs = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            elevenLegs.add(List.of("obs-x" + i, 1));
        }
        generate("req-t9", "surveyor-a", elevenLegs).andExpect(status().isBadRequest());
    }

    @Test
    void generatedTraverseIsFrozenAgainstLaterObservationChanges() throws Exception {
        createSyntheticObservations();
        MvcResult result = generate("req-t10", "surveyor-a",
                legsOf("obs-t1", 1, "obs-t2", 1, "obs-t3", 1))
                .andExpect(status().isCreated())
                .andReturn();
        String traverseId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("traverseId").asText();

        // 生成后修改 obs-t1 的位移：只改变当前记录，不回改成果
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-m2");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点obs-t1");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "备注");
        mergeBody.put("dx", "5");
        mergeBody.put("dy", "5");
        mergeBody.put("w", "500");
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.dx").value("5"));

        mockMvc.perform(get("/api/traverses/{id}", traverseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs[0].observationId").value("obs-t1"))
                .andExpect(jsonPath("$.legs[0].version").value(1))
                .andExpect(jsonPath("$.legs[0].dx").value("1"))
                .andExpect(jsonPath("$.legs[0].adjustedDx").value("0.990000"));
    }

    @Test
    void getUnknownTraverseReturns404() throws Exception {
        mockMvc.perform(get("/api/traverses/{id}", "no-such-traverse"))
                .andExpect(status().isNotFound());
    }
}
