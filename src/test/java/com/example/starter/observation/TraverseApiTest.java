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
 * 闭合导线成果生成 API 的主流程、失败分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
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
        jdbcTemplate.update("DELETE FROM traverse_result_edge");
        jdbcTemplate.update("DELETE FROM traverse_result");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createObs(String requestId, String observationId,
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

    private ResultActions generate(String requestId, String resultId, String operator,
                                   List<String[]> edgeRefs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resultId", resultId);
        body.put("operator", operator);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String[] ref : edgeRefs) {
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("observationId", ref[0]);
            edge.put("version", Integer.valueOf(ref[1]));
            edges.add(edge);
        }
        body.put("edges", edges);
        return mockMvc.perform(post("/api/traverses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getTraverse(String resultId) throws Exception {
        return mockMvc.perform(get("/api/traverses/{id}", resultId));
    }

    private ResultActions mergeNote(String requestId, String observationId, int baseVersion,
                                    String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点" + observationId);
        body.put("reading", "1.0");
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteObs(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeDisplacement(String requestId, String observationId, int baseVersion,
                                            String dx, String dy, String w) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点" + observationId);
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("dx", dx);
        body.put("dy", dy);
        body.put("w", w);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void setupSampleTriangle() throws Exception {
        createObs("req-t-c1", "obs-t1", "1", "0", "1").andExpect(status().isCreated());
        createObs("req-t-c2", "obs-t2", "0", "1", "1").andExpect(status().isCreated());
        createObs("req-t-c3", "obs-t3", "-0.94", "-1", "1").andExpect(status().isCreated());
    }

    private List<String[]> sampleRefs() {
        return List.of(new String[]{"obs-t1", "1"}, new String[]{"obs-t2", "1"},
                new String[]{"obs-t3", "1"});
    }

    private int traverseCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM traverse_result", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程 ----------

    @Test
    void generateWithSampleDataProducesExactClosure() throws Exception {
        setupSampleTriangle();
        generate("req-t-g1", "trv-1", "surveyor-a", sampleRefs())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultId").value("trv-1"))
                .andExpect(jsonPath("$.edgeCount").value(3))
                .andExpect(jsonPath("$.closureDx").value("0.06"))
                .andExpect(jsonPath("$.closureDy").value("0"))
                .andExpect(jsonPath("$.totalW").value("3"))
                .andExpect(jsonPath("$.edges[0].observationId").value("obs-t1"))
                .andExpect(jsonPath("$.edges[0].correctedDx").value("0.980000"))
                .andExpect(jsonPath("$.edges[0].correctedDy").value("0.000000"))
                .andExpect(jsonPath("$.edges[1].correctedDx").value("-0.020000"))
                .andExpect(jsonPath("$.edges[1].correctedDy").value("1.000000"))
                .andExpect(jsonPath("$.edges[2].correctedDx").value("-0.960000"))
                .andExpect(jsonPath("$.edges[2].correctedDy").value("-1.000000"));

        // 修正值逐边落库且修正和精确为 0（数据库内核对）
        List<String> allDx = jdbcTemplate.queryForList(
                "SELECT corrected_dx FROM traverse_result_edge WHERE result_id = 'trv-1' ORDER BY edge_index",
                String.class);
        assertThat(allDx).containsExactly("0.980000", "-0.020000", "-0.960000");
        List<String> allDy = jdbcTemplate.queryForList(
                "SELECT corrected_dy FROM traverse_result_edge WHERE result_id = 'trv-1' ORDER BY edge_index",
                String.class);
        assertThat(allDy).containsExactly("0.000000", "1.000000", "-1.000000");

        // 成果可查询且内容一致
        getTraverse("trv-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closureDx").value("0.06"))
                .andExpect(jsonPath("$.edges[2].observationId").value("obs-t3"))
                .andExpect(jsonPath("$.edges[2].observationVersion").value(1));
    }

    @Test
    void closureErrorExceededReturns422AndWritesNothing() throws Exception {
        createObs("req-t-c1", "obs-t1", "1", "0", "1").andExpect(status().isCreated());
        createObs("req-t-c2", "obs-t2", "0", "1", "1").andExpect(status().isCreated());
        // 末边 (-0.899,-1)：闭合差 dx = 0.101 > 0.1，越限
        createObs("req-t-c3", "obs-t3", "-0.899", "-1", "1").andExpect(status().isCreated());

        generate("req-t-g2", "trv-2", "surveyor-a", sampleRefs())
                .andExpect(status().isUnprocessableEntity());
        assertThat(traverseCount()).isZero();
        // 失败不占键：修正末边位移到 (-0.94,-1) 后，同一 requestId 携带新期望版本可成功
        mergeDisplacement("req-t-m3", "obs-t3", 1, "-0.94", "-1", "1").andExpect(status().isOk());
        generate("req-t-g2", "trv-2", "surveyor-a",
                List.of(new String[]{"obs-t1", "1"}, new String[]{"obs-t2", "1"},
                        new String[]{"obs-t3", "2"}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closureDx").value("0.06"));
        assertThat(traverseCount()).isEqualTo(1);
    }

    @Test
    void closureErrorAtLimitIsAccepted() throws Exception {
        createObs("req-t-c1", "obs-t1", "1", "0", "1").andExpect(status().isCreated());
        createObs("req-t-c2", "obs-t2", "0", "1", "1").andExpect(status().isCreated());
        // 闭合差 dx 恰为 0.1：未超过上限，可生成
        createObs("req-t-c3", "obs-t3", "-0.9", "-1", "1").andExpect(status().isCreated());
        generate("req-t-g3", "trv-3", "surveyor-a", sampleRefs())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.closureDx").value("0.1"));
    }

    // ---------- 引用校验 ----------

    @Test
    void missingDisplacementReturns422() throws Exception {
        createObs("req-t-c1", "obs-t1", "1", "0", "1").andExpect(status().isCreated());
        createObs("req-t-c2", "obs-t2", "0", "1", "1").andExpect(status().isCreated());
        createObs("req-t-c3", "obs-t3", null, null, null).andExpect(status().isCreated());

        generate("req-t-g4", "trv-4", "surveyor-a", sampleRefs())
                .andExpect(status().isUnprocessableEntity());
        assertThat(traverseCount()).isZero();
    }

    @Test
    void tombstoneReferenceReturns422() throws Exception {
        setupSampleTriangle();
        deleteObs("req-t-d3", "obs-t3", 1).andExpect(status().isOk());
        generate("req-t-g5", "trv-5", "surveyor-a",
                List.of(new String[]{"obs-t1", "1"}, new String[]{"obs-t2", "1"},
                        new String[]{"obs-t3", "2"}))
                .andExpect(status().isUnprocessableEntity());
        assertThat(traverseCount()).isZero();
    }

    @Test
    void staleVersionReturns409() throws Exception {
        setupSampleTriangle();
        mergeNote("req-t-m1", "obs-t1", 1, "新备注").andExpect(status().isOk());
        generate("req-t-g6", "trv-6", "surveyor-a", sampleRefs())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(traverseCount()).isZero();
    }

    @Test
    void missingObservationReturns404() throws Exception {
        setupSampleTriangle();
        generate("req-t-g7", "trv-7", "surveyor-a",
                List.of(new String[]{"obs-t1", "1"}, new String[]{"obs-t2", "1"},
                        new String[]{"obs-x", "1"}))
                .andExpect(status().isNotFound());
        assertThat(traverseCount()).isZero();
    }

    @Test
    void duplicateObservationReturns400() throws Exception {
        setupSampleTriangle();
        generate("req-t-g8", "trv-8", "surveyor-a",
                List.of(new String[]{"obs-t1", "1"}, new String[]{"obs-t2", "1"},
                        new String[]{"obs-t1", "1"}))
                .andExpect(status().isBadRequest());
        assertThat(traverseCount()).isZero();
    }

    @Test
    void tooFewEdgesReturns400() throws Exception {
        setupSampleTriangle();
        generate("req-t-g9", "trv-9", "surveyor-a",
                List.of(new String[]{"obs-t1", "1"}, new String[]{"obs-t2", "1"}))
                .andExpect(status().isBadRequest());
        assertThat(traverseCount()).isZero();
    }

    // ---------- 冻结与幂等 ----------

    @Test
    void resultFrozenAfterObservationChanges() throws Exception {
        setupSampleTriangle();
        generate("req-t-g10", "trv-10", "surveyor-a", sampleRefs()).andExpect(status().isCreated());

        // 后续修改观测只改变当前记录：成果不倒改
        mergeNote("req-t-m2", "obs-t1", 1, "修改后的备注").andExpect(status().isOk());
        getTraverse("trv-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edges[0].observationVersion").value(1))
                .andExpect(jsonPath("$.edges[0].correctedDx").value("0.980000"));
        // 用旧版本再次生成被拒绝（版本非当前）
        generate("req-t-g11", "trv-11", "surveyor-a", sampleRefs())
                .andExpect(status().isConflict());
        assertThat(traverseCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        setupSampleTriangle();
        generate("req-t-g12", "trv-12", "surveyor-a", sampleRefs()).andExpect(status().isCreated());
        generate("req-t-g12", "trv-12", "surveyor-a", sampleRefs())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultId").value("trv-12"))
                .andExpect(jsonPath("$.closureDx").value("0.06"));
        assertThat(traverseCount()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-t-g12'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        setupSampleTriangle();
        generate("req-t-g13", "trv-13", "surveyor-a", sampleRefs()).andExpect(status().isCreated());
        // 同键异参：操作者不同 → 409
        generate("req-t-g13", "trv-13", "surveyor-b", sampleRefs())
                .andExpect(status().isConflict());
        // 同键异参：边顺序不同 → 409
        generate("req-t-g13", "trv-13", "surveyor-a",
                List.of(new String[]{"obs-t2", "1"}, new String[]{"obs-t1", "1"},
                        new String[]{"obs-t3", "1"}))
                .andExpect(status().isConflict());
        assertThat(traverseCount()).isEqualTo(1);
    }

    @Test
    void duplicateResultIdReturns409() throws Exception {
        setupSampleTriangle();
        generate("req-t-g14", "trv-14", "surveyor-a", sampleRefs()).andExpect(status().isCreated());
        generate("req-t-g15", "trv-14", "surveyor-a", sampleRefs())
                .andExpect(status().isConflict());
        assertThat(traverseCount()).isEqualTo(1);
    }

    @Test
    void getMissingTraverseReturns404() throws Exception {
        getTraverse("trv-x").andExpect(status().isNotFound());
    }
}
