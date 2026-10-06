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
 * 位移完整字段组（dx/dy/w）的创建校验、三方合并与冲突解决测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisplacementMergeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions create(String requestId, String observationId,
                                 String dx, String dy, String w) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (dx != null) {
            body.put("dx", dx);
        }
        if (dy != null) {
            body.put("dy", dy);
        }
        if (w != null) {
            body.put("w", w);
        }
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String note, String dx, String dy, String w) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", note);
        if (dx != null) {
            body.put("dx", dx);
            body.put("dy", dy);
            body.put("w", w);
        }
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion,
                                  String dx, String dy, String w,
                                  Map<String, String> selections) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (dx != null) {
            body.put("dx", dx);
            body.put("dy", dy);
            body.put("w", w);
        }
        body.put("selections", selections);
        body.put("operator", "surveyor-a");
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

    // ---------- 创建与校验 ----------

    @Test
    void createWithDisplacementRoundTrips() throws Exception {
        create("req-dp-1", "obs-d1", "1.5", "-0.25", "2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dx").value("1.5"))
                .andExpect(jsonPath("$.dy").value("-0.25"))
                .andExpect(jsonPath("$.w").value("2"));
        getCurrent("obs-d1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dx").value("1.5"))
                .andExpect(jsonPath("$.w").value("2"));
    }

    @Test
    void createWithoutDisplacementKeepsOriginalContract() throws Exception {
        create("req-dp-2", "obs-d2", null, null, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dx").doesNotExist())
                .andExpect(jsonPath("$.dy").doesNotExist())
                .andExpect(jsonPath("$.w").doesNotExist());
    }

    @Test
    void partialDisplacementGroupReturns400() throws Exception {
        create("req-dp-3", "obs-d3", "1.0", null, null).andExpect(status().isBadRequest());
        create("req-dp-4", "obs-d3", "1.0", "0.5", null).andExpect(status().isBadRequest());
        assertThat(versionCount("obs-d3")).isZero();
    }

    @Test
    void nonPositiveLengthReturns400() throws Exception {
        create("req-dp-5", "obs-d4", "1.0", "0.5", "0").andExpect(status().isBadRequest());
        create("req-dp-6", "obs-d4", "1.0", "0.5", "-2").andExpect(status().isBadRequest());
        assertThat(versionCount("obs-d4")).isZero();
    }

    @Test
    void invalidDisplacementFormatReturns400() throws Exception {
        create("req-dp-7", "obs-d5", "1.2345", "0.5", "2").andExpect(status().isBadRequest());
        create("req-dp-8", "obs-d5", "abc", "0.5", "2").andExpect(status().isBadRequest());
        assertThat(versionCount("obs-d5")).isZero();
    }

    // ---------- 三方合并 ----------

    @Test
    void mergeAcceptsCandidateGroupAsWhole() throws Exception {
        create("req-dp-9", "obs-d6", "1", "0", "1").andExpect(status().isCreated());
        // 当前未改：整组接受候选（只改 dx，dy/w 也随组来自候选，不拆拼来源）
        merge("req-dp-10", "obs-d6", 1, "备注", "2", "0", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.dx").value("2"))
                .andExpect(jsonPath("$.dy").value("0"))
                .andExpect(jsonPath("$.w").value("1"));
    }

    @Test
    void mergeKeepsCurrentGroupWhenCandidateUnchanged() throws Exception {
        create("req-dp-11", "obs-d7", "1", "0", "1").andExpect(status().isCreated());
        // 服务端前进到 v2：整组改为 (3,0,1)
        merge("req-dp-12", "obs-d7", 1, "备注", "3", "0", "1").andExpect(status().isOk());
        // 离线端基于 v1 只改备注、位移组未改：保留当前组
        merge("req-dp-13", "obs-d7", 1, "离线备注", "1", "0", "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.note").value("离线备注"))
                .andExpect(jsonPath("$.dx").value("3"));
    }

    @Test
    void mergeDisplacementConflictReturns409() throws Exception {
        create("req-dp-14", "obs-d8", "1", "0", "1").andExpect(status().isCreated());
        merge("req-dp-15", "obs-d8", 1, "备注", "3", "0", "1").andExpect(status().isOk());
        // 离线端基于 v1 整组改为 (2,0,1)：与服务端 (3,0,1) 冲突
        merge("req-dp-16", "obs-d8", 1, "备注", "2", "0", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("displacement"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("obs-d8")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.dx").value("3"));
        assertThat(versionCount("obs-d8")).isEqualTo(2);
    }

    @Test
    void mergeNumericallyEqualGroupDoesNotAddVersion() throws Exception {
        create("req-dp-17", "obs-d9", "1", "0", "1").andExpect(status().isCreated());
        // 候选组 (1.0, 0.00, 1.000) 与基线数值相等：视为未改，结果与当前相同，不加版本
        merge("req-dp-18", "obs-d9", 1, "备注", "1.0", "0.00", "1.000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.dx").value("1"));
        assertThat(versionCount("obs-d9")).isEqualTo(1);
    }

    // ---------- 冲突解决 ----------

    @Test
    void resolveDisplacementConflictAppliesSelection() throws Exception {
        create("req-dp-19", "obs-d10", "1", "0", "1").andExpect(status().isCreated());
        merge("req-dp-20", "obs-d10", 1, "备注", "3", "0", "1").andExpect(status().isOk());
        merge("req-dp-21", "obs-d10", 1, "备注", "2", "0", "1").andExpect(status().isConflict());

        // 选择候选组：整组取 (2,0,1)
        resolve("req-dp-22", "res-dp-1", "obs-d10", 1, 2, "2", "0", "1",
                Map.of("displacement", "CANDIDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.conflictFields[0]").value("displacement"))
                .andExpect(jsonPath("$.dx").value("2"))
                .andExpect(jsonPath("$.dy").value("0"))
                .andExpect(jsonPath("$.w").value("1"));
        getCurrent("obs-d10")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.dx").value("2"));
    }

    @Test
    void resolveDisplacementConflictKeepsCurrent() throws Exception {
        create("req-dp-23", "obs-d11", "1", "0", "1").andExpect(status().isCreated());
        merge("req-dp-24", "obs-d11", 1, "备注", "3", "0", "1").andExpect(status().isOk());
        merge("req-dp-25", "obs-d11", 1, "备注", "2", "0", "1").andExpect(status().isConflict());

        // 选择当前组：内容无变化，不加版本，仅保存解决记录
        resolve("req-dp-26", "res-dp-2", "obs-d11", 1, 2, "2", "0", "1",
                Map.of("displacement", "CURRENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.dx").value("3"));
        assertThat(versionCount("obs-d11")).isEqualTo(2);
    }
}
