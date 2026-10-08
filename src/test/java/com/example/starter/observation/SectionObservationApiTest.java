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
 * SECTION 观测类型 API 测试：断面号/横向/高程作为完整版本组参与创建、三方合并、
 * 冲突解决与删除，版本组不可拆分拼接（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SectionObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM section_result");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createSection(String requestId, String observationId,
                                        String sectionNo, Integer lateralMm, Integer elevationMm)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("observationType", "SECTION");
        body.put("sectionNo", sectionNo);
        body.put("lateralMm", lateralMm);
        body.put("elevationMm", elevationMm);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeSection(String requestId, String observationId, int baseVersion,
                                       String sectionNo, Integer lateralMm, Integer elevationMm)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("sectionNo", sectionNo);
        body.put("lateralMm", lateralMm);
        body.put("elevationMm", elevationMm);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolveSection(String requestId, String resolutionId, String observationId,
                                         int baseVersion, int expectedCurrentVersion,
                                         String sectionNo, Integer lateralMm, Integer elevationMm,
                                         Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("sectionNo", sectionNo);
        body.put("lateralMm", lateralMm);
        body.put("elevationMm", elevationMm);
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createSectionObservationStoresCompleteGroup() throws Exception {
        createSection("req-s1", "sec-1", "S1", 0, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationType").value("SECTION"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sectionNo").value("S1"))
                .andExpect(jsonPath("$.lateralMm").value(0))
                .andExpect(jsonPath("$.elevationMm").value(0))
                .andExpect(jsonPath("$.location").doesNotExist())
                .andExpect(jsonPath("$.reading").doesNotExist());
        getCurrent("sec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sectionNo").value("S1"))
                .andExpect(jsonPath("$.lateralMm").value(0));
        assertThat(versionCount("sec-1")).isEqualTo(1);
    }

    @Test
    void createSectionWithIncompleteGroupReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-s2");
        body.put("observationId", "sec-1");
        body.put("observationType", "SECTION");
        body.put("sectionNo", "S1");
        body.put("lateralMm", 0);
        // 缺 elevationMm：版本组不完整
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("sec-1")).isZero();
    }

    @Test
    void createSectionWithFieldFieldsReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-s3");
        body.put("observationId", "sec-1");
        body.put("observationType", "SECTION");
        body.put("sectionNo", "S1");
        body.put("lateralMm", 0);
        body.put("elevationMm", 0);
        body.put("location", "不应携带");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("sec-1")).isZero();
    }

    @Test
    void createFieldWithSectionFieldsReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-s4");
        body.put("observationId", "obs-1");
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("sectionNo", "S1");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isZero();
    }

    @Test
    void createWithInvalidTypeReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-s5");
        body.put("observationId", "obs-1");
        body.put("observationType", "BOGUS");
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 三方合并（版本组整体） ----------

    @Test
    void mergeSectionAcceptsCandidateGroupWhenCurrentUnchanged() throws Exception {
        createSection("req-m1", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        mergeSection("req-m2", "sec-1", 1, "S1", 0, 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.elevationMm").value(5));
        assertThat(versionCount("sec-1")).isEqualTo(2);
    }

    @Test
    void mergeSectionGroupConflictDoesNotSplice() throws Exception {
        createSection("req-m3", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        // 服务端把高程改为 5（版本 2）
        mergeSection("req-m4", "sec-1", 1, "S1", 0, 5).andExpect(status().isOk());
        // 离线端基于版本 1 把横向改为 10：版本组整体冲突，不得拼接横向与高程
        mergeSection("req-m5", "sec-1", 1, "S1", 10, 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("sectionPoint"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("sec-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lateralMm").value(0))
                .andExpect(jsonPath("$.elevationMm").value(5));
        assertThat(versionCount("sec-1")).isEqualTo(2);
    }

    @Test
    void mergeSectionBothSidesSameChangeAccepted() throws Exception {
        createSection("req-m6", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        mergeSection("req-m7", "sec-1", 1, "S1", 10, 5).andExpect(status().isOk());
        // 离线端也改为相同版本组：接受且与当前相同，不加版本
        mergeSection("req-m8", "sec-1", 1, "S1", 10, 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("sec-1")).isEqualTo(2);
    }

    @Test
    void mergeSectionCandidateUnchangedKeepsCurrent() throws Exception {
        createSection("req-m9", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        mergeSection("req-m10", "sec-1", 1, "S1", 0, 5).andExpect(status().isOk());
        // 离线端未改（候选等于基线）：保留当前，不加版本
        mergeSection("req-m11", "sec-1", 1, "S1", 0, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.elevationMm").value(5));
        assertThat(versionCount("sec-1")).isEqualTo(2);
    }

    @Test
    void mergeSectionWithIncompleteGroupReturns400() throws Exception {
        createSection("req-m12", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-m13");
        body.put("baseVersion", 1);
        body.put("sectionNo", "S1");
        body.put("lateralMm", 10);
        // 缺 elevationMm
        mockMvc.perform(post("/api/observations/{id}/merge", "sec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("sec-1")).isEqualTo(1);
    }

    // ---------- 冲突解决 ----------

    @Test
    void resolveSectionConflictAppliesWholeGroupSelection() throws Exception {
        createSection("req-r1", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        mergeSection("req-r2", "sec-1", 1, "S1", 0, 5).andExpect(status().isOk());
        // 解决：选择候选版本组（横向 10、高程 3），整组生效
        resolveSection("req-r3", "res-1", "sec-1", 1, 2, "S1", 10, 3,
                Map.of("sectionPoint", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("sectionPoint"))
                .andExpect(jsonPath("$.sectionNo").value("S1"))
                .andExpect(jsonPath("$.lateralMm").value(10))
                .andExpect(jsonPath("$.elevationMm").value(3));
        getCurrent("sec-1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.lateralMm").value(10))
                .andExpect(jsonPath("$.elevationMm").value(3));
        // 解决记录可按 resolutionId 查询
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lateralMm").value(10));
    }

    @Test
    void resolveSectionWithFieldSelectionKeyReturns400() throws Exception {
        createSection("req-r4", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        mergeSection("req-r5", "sec-1", 1, "S1", 0, 5).andExpect(status().isOk());
        // 选择键必须是 sectionPoint：用 FIELD 字段名不覆盖版本组冲突
        resolveSection("req-r6", "res-2", "sec-1", 1, 2, "S1", 10, 3,
                Map.of("location", "CANDIDATE"), "operator-a")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("sec-1")).isEqualTo(2);
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deletedSectionRejectsMergeWith410() throws Exception {
        createSection("req-d1", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-d2");
        body.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "sec-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.sectionNo").doesNotExist());
        mergeSection("req-d3", "sec-1", 1, "S1", 0, 5).andExpect(status().isGone());
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysSectionCreate() throws Exception {
        createSection("req-i1", "sec-1", "S1", 0, 0).andExpect(status().isCreated());
        createSection("req-i1", "sec-1", "S1", 0, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("sec-1")).isEqualTo(1);
        createSection("req-i1", "sec-1", "S1", 0, 5).andExpect(status().isConflict());
        assertThat(versionCount("sec-1")).isEqualTo(1);
    }
}
