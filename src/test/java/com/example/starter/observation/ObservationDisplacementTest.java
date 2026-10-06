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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测位移字段组（dx/dy/w）的创建校验、三方合并与冲突解决测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationDisplacementTest {

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

    private ResultActions create(String requestId, String observationId, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.putAll(extra);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String note, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", note);
        body.putAll(extra);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> displacement(String dx, String dy, String w) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dx", dx);
        extra.put("dy", dy);
        extra.put("w", w);
        return extra;
    }

    @Test
    void createWithDisplacementReturnsGroupAndFreezesVersion() throws Exception {
        create("req-c1", "obs-d1", displacement("1.5", "-0.25", "100"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dx").value("1.5"))
                .andExpect(jsonPath("$.dy").value("-0.25"))
                .andExpect(jsonPath("$.w").value("100"));

        mockMvc.perform(get("/api/observations/{id}", "obs-d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dx").value("1.5"));
        // 位移随版本快照冻结
        mockMvc.perform(get("/api/observations/{id}/versions/{v}", "obs-d1", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dx").value("1.5"))
                .andExpect(jsonPath("$.w").value("100"));
    }

    @Test
    void plainObservationKeepsOriginalContract() throws Exception {
        create("req-c2", "obs-d2", Map.of())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dx").doesNotExist())
                .andExpect(jsonPath("$.dy").doesNotExist())
                .andExpect(jsonPath("$.w").doesNotExist());
    }

    @Test
    void partialDisplacementGroupReturns400() throws Exception {
        Map<String, Object> onlyDx = new LinkedHashMap<>();
        onlyDx.put("dx", "1");
        onlyDx.put("dy", "0");
        create("req-c3", "obs-d3", onlyDx).andExpect(status().isBadRequest());
    }

    @Test
    void nonPositiveWeightReturns400() throws Exception {
        create("req-c4", "obs-d4", displacement("1", "0", "0"))
                .andExpect(status().isBadRequest());
        create("req-c5", "obs-d5", displacement("1", "0", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooManyFractionDigitsReturns400() throws Exception {
        create("req-c6", "obs-d6", displacement("1.0001", "0", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mergeKeepsCurrentDisplacementWhenCandidateUnchanged() throws Exception {
        create("req-c7", "obs-d7", displacement("1", "0", "100")).andExpect(status().isCreated());
        // 候选只改备注、位移组未改：保留当前位移
        merge("req-m7", "obs-d7", 1, "新备注", displacement("1", "0", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.note").value("新备注"))
                .andExpect(jsonPath("$.dx").value("1"));
    }

    @Test
    void mergeAcceptsCandidateDisplacementWhenCurrentUnchanged() throws Exception {
        create("req-c8", "obs-d8", displacement("1", "0", "100")).andExpect(status().isCreated());
        merge("req-m8", "obs-d8", 1, "备注", displacement("2", "1", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.dx").value("2"))
                .andExpect(jsonPath("$.dy").value("1"))
                .andExpect(jsonPath("$.w").value("200"));
        // 历史版本仍冻结原位移
        mockMvc.perform(get("/api/observations/{id}/versions/{v}", "obs-d8", 1))
                .andExpect(jsonPath("$.dx").value("1"));
    }

    @Test
    void mergeWithSameDisplacementChangeOnBothSidesAddsNoVersion() throws Exception {
        create("req-c9", "obs-d9", displacement("1", "0", "100")).andExpect(status().isCreated());
        merge("req-m9a", "obs-d9", 1, "备注", displacement("2", "0", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 另一边基于 v1 改为相同位移：与当前一致，不加版本
        merge("req-m9b", "obs-d9", 1, "备注", displacement("2", "0", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void mergeWithConflictingDisplacementReturns409() throws Exception {
        create("req-c10", "obs-d10", displacement("1", "0", "100")).andExpect(status().isCreated());
        merge("req-m10a", "obs-d10", 1, "备注", displacement("2", "0", "100"))
                .andExpect(status().isOk());
        // 离线端基于 v1 改另一组位移：整组冲突
        merge("req-m10b", "obs-d10", 1, "备注", displacement("3", "1", "300"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("displacement"))
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    void resolveDisplacementConflictAppliesSelectionAsWholeGroup() throws Exception {
        create("req-c11", "obs-d11", displacement("1", "0", "100")).andExpect(status().isCreated());
        merge("req-m11a", "obs-d11", 1, "备注", displacement("2", "0", "100"))
                .andExpect(status().isOk());

        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-r11");
        resolveBody.put("resolutionId", "res-11");
        resolveBody.put("baseVersion", 1);
        resolveBody.put("expectedCurrentVersion", 2);
        resolveBody.put("location", "站点A");
        resolveBody.put("reading", "1.0");
        resolveBody.put("note", "备注");
        resolveBody.put("dx", "3");
        resolveBody.put("dy", "1");
        resolveBody.put("w", "300");
        resolveBody.put("selections", Map.of("displacement", "CANDIDATE"));
        resolveBody.put("operator", "surveyor-b");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-d11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflictFields[0]").value("displacement"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.dx").value("3"))
                .andExpect(jsonPath("$.dy").value("1"))
                .andExpect(jsonPath("$.w").value("300"));

        mockMvc.perform(get("/api/observations/{id}", "obs-d11"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.dx").value("3"));
    }

    @Test
    void resolveWithoutCoveringDisplacementConflictReturns400() throws Exception {
        create("req-c12", "obs-d12", displacement("1", "0", "100")).andExpect(status().isCreated());
        merge("req-m12a", "obs-d12", 1, "备注", displacement("2", "0", "100"))
                .andExpect(status().isOk());

        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-r12");
        resolveBody.put("resolutionId", "res-12");
        resolveBody.put("baseVersion", 1);
        resolveBody.put("expectedCurrentVersion", 2);
        resolveBody.put("location", "站点A");
        resolveBody.put("reading", "1.0");
        resolveBody.put("note", "备注");
        resolveBody.put("dx", "3");
        resolveBody.put("dy", "1");
        resolveBody.put("w", "300");
        resolveBody.put("selections", Map.of());
        resolveBody.put("operator", "surveyor-b");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-d12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isBadRequest());
    }
}
