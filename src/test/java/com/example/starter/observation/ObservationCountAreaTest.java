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
 * 计数面积组测试：新字段校验、整组三方合并（不跨提交拼组）、组冲突解决与冲突标记联动。
 * 真实 H2 内存库（MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationCountAreaTest {

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

    private ResultActions create(String requestId, String id, Object count, Object sampleArea)
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

    private ResultActions merge(String requestId, String id, int baseVersion, String location,
                                Object count, Object sampleArea) throws Exception {
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

    private ResultActions getCurrent(String id) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", id));
    }

    // ---------- 新字段校验与冻结 ----------

    @Test
    void createWithCountAreaReturnsAndFreezesFields() throws Exception {
        create("req-g1", "obs-1", 3, 7)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.sampleArea").value(7));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.sampleArea").value(7));
        mockMvc.perform(get("/api/observations/{id}/versions/{v}", "obs-1", 1))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.sampleArea").value(7));
    }

    @Test
    void countAreaMustBeProvidedAsGroup() throws Exception {
        // 只给计数或只给面积：400
        create("req-g2", "obs-1", 3, null).andExpect(status().isBadRequest());
        create("req-g3", "obs-1", null, 7).andExpect(status().isBadRequest());
        // 计数为负、面积非正：400
        create("req-g4", "obs-1", -1, 7).andExpect(status().isBadRequest());
        create("req-g5", "obs-1", 3, 0).andExpect(status().isBadRequest());
        // 合并同样要求成组
        create("req-g6", "obs-1", 1, 2).andExpect(status().isCreated());
        merge("req-g7", "obs-1", 1, "站点A", 5, null).andExpect(status().isBadRequest());
        getCurrent("obs-1").andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 整组三方合并 ----------

    @Test
    void mergeTakesCountAreaGroupAsUnit() throws Exception {
        create("req-h1", "obs-1", 1, 2).andExpect(status().isCreated());
        // 当前未改：整组接受候选 (5, 9)
        merge("req-h2", "obs-1", 1, "站点A", 5, 9)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.sampleArea").value(9));
    }

    @Test
    void mergeGroupConflictDoesNotPieceAcrossSubmissions() throws Exception {
        create("req-h3", "obs-1", 1, 2).andExpect(status().isCreated());
        // 服务端把组改为 (3, 2)：v2
        merge("req-h4", "obs-1", 1, "站点A", 3, 2).andExpect(status().isOk());
        // 离线端基于 v1 把组改为 (1, 9)：组冲突，不能拿当前计数 3 拼候选面积 9
        merge("req-h5", "obs-1", 1, "站点A", 1, 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("countArea"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        // 不写入任何部分结果：组保持 (3, 2)
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.sampleArea").value(2));
    }

    @Test
    void resolveCountAreaConflictAppliesSelectionAsGroup() throws Exception {
        create("req-h6", "obs-1", 1, 2).andExpect(status().isCreated());
        merge("req-h7", "obs-1", 1, "站点A", 3, 2).andExpect(status().isOk());
        merge("req-h8", "obs-1", 1, "站点A", 1, 9).andExpect(status().isConflict());

        // 显式解决：选择候选组 (1, 9)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-h9");
        body.put("resolutionId", "res-h9");
        body.put("baseVersion", 1);
        body.put("expectedCurrentVersion", 2);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("count", 1);
        body.put("sampleArea", 9);
        body.put("selections", Map.of("countArea", "CANDIDATE"));
        body.put("operator", "operator-a");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.conflictFields[0]").value("countArea"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.sampleArea").value(9));

        // 解决记录冻结候选组
        Integer candidateCount = jdbcTemplate.queryForObject(
                "SELECT candidate_count FROM conflict_resolution WHERE resolution_id = 'res-h9'",
                Integer.class);
        Integer candidateArea = jdbcTemplate.queryForObject(
                "SELECT candidate_sample_area FROM conflict_resolution WHERE resolution_id = 'res-h9'",
                Integer.class);
        assertThat(candidateCount).isEqualTo(1);
        assertThat(candidateArea).isEqualTo(9);

        // 冲突已解决：冲突标记清除，可用于生成估计成果
        Boolean conflictPending = jdbcTemplate.queryForObject(
                "SELECT conflict_pending FROM observation_current WHERE observation_id = 'obs-1'",
                Boolean.class);
        assertThat(conflictPending).isFalse();
    }

    @Test
    void mergeConflictSetsPendingFlagAndEstimateRejects() throws Exception {
        create("req-k1", "obs-1", 1, 2).andExpect(status().isCreated());
        create("req-k2", "obs-2", 1, 1).andExpect(status().isCreated());
        merge("req-k3", "obs-1", 1, "站点B", 1, 2).andExpect(status().isOk());
        // 合并冲突：未解决冲突标记置位
        merge("req-k4", "obs-1", 1, "站点C", 1, 2).andExpect(status().isConflict());
        Boolean conflictPending = jdbcTemplate.queryForObject(
                "SELECT conflict_pending FROM observation_current WHERE observation_id = 'obs-1'",
                Boolean.class);
        assertThat(conflictPending).isTrue();

        // 存在未解决冲突的观测：估计 422
        Map<String, Object> estimateBody = new LinkedHashMap<>();
        estimateBody.put("requestId", "req-k5");
        estimateBody.put("estimateId", "est-k5");
        estimateBody.put("operator", "operator-a");
        estimateBody.put("layers", java.util.List.of(
                Map.of("totalArea", 10, "members",
                        java.util.List.of(Map.of("observationId", "obs-1", "expectedVersion", 2))),
                Map.of("totalArea", 10, "members",
                        java.util.List.of(Map.of("observationId", "obs-2", "expectedVersion", 1)))));
        mockMvc.perform(post("/api/estimates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(estimateBody)))
                .andExpect(status().isUnprocessableEntity());
    }
}
