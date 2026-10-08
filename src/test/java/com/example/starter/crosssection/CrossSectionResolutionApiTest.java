package com.example.starter.crosssection;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 断面冲突裁决、成果冻结不重算的真实 H2 事务测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrossSectionResolutionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM cross_section_result_point");
        jdbcTemplate.update("DELETE FROM cross_section_result");
        jdbcTemplate.update("DELETE FROM cross_section_resolution");
        jdbcTemplate.update("DELETE FROM cross_section_pending_conflict");
        jdbcTemplate.update("DELETE FROM cross_section_version");
        jdbcTemplate.update("DELETE FROM cross_section_current");
    }

    private ResultActions createPoint(String reqId, String obsId, String section, int x, int z)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("observationId", obsId);
        body.put("sectionNo", section);
        body.put("xMm", x);
        body.put("zMm", z);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/points")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergePoint(String reqId, String obsId, int base, String section, int x, int z)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("baseVersion", base);
        body.put("sectionNo", section);
        body.put("xMm", x);
        body.put("zMm", z);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/points/{id}/merge", obsId)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String reqId, String resId, String obsId, int base, int expected,
                                  String section, int x, int z, Map<String, String> selections)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("resolutionId", resId);
        body.put("baseVersion", base);
        body.put("expectedCurrentVersion", expected);
        body.put("sectionNo", section);
        body.put("xMm", x);
        body.put("zMm", z);
        body.put("selections", selections);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/points/{id}/resolve", obsId)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, String> selection(String field, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(field, value);
        return map;
    }

    private static Map<String, Object> ref(String obsId, int version) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("observationId", obsId);
        ref.put("expectedVersion", version);
        return ref;
    }

    private ResultActions createResult(String reqId, String resultId, int h,
                                       List<Map<String, Object>> points) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", reqId);
        body.put("resultId", resultId);
        body.put("waterLevelMm", h);
        body.put("points", points);
        body.put("operator", "op-1");
        return mockMvc.perform(post("/api/cross-sections/results")
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void resolveCandidateClearsPendingAndAllowsResult() throws Exception {
        createPoint("req-1", "pt-a", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-2", "pt-b", "S1", 10, 10).andExpect(status().isCreated());
        mergePoint("req-3", "pt-a", 1, "S1", 0, 10).andExpect(status().isOk());
        // 离线基于版本 1 改高程为 8：与版本 2 的高程 10 冲突，留未决标记
        mergePoint("req-4", "pt-a", 1, "S1", 0, 8).andExpect(status().isConflict());
        mockMvc.perform(get("/api/cross-sections/points/pt-a/pending-conflict"))
                .andExpect(status().isOk());

        // 裁决接受候选：生成版本 3（高程 8），清除标记
        resolve("req-5", "res-1", "pt-a", 1, 2, "S1", 0, 8, selection("zMm", "CANDIDATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.zMm").value(8))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("zMm"))
                .andExpect(jsonPath("$.selections.zMm").value("CANDIDATE"));
        mockMvc.perform(get("/api/cross-sections/points/pt-a/pending-conflict"))
                .andExpect(status().isNotFound());

        // 裁决记录可查，且成果现在可创建（新版本下地形 (0,8)->(10,10)，H=5 全程干，面积 0）
        mockMvc.perform(get("/api/cross-sections/resolutions/res-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionId").value("res-1"));
        createResult("req-6", "result-1", 5, List.of(ref("pt-a", 3), ref("pt-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaNumerator").value(0))
                .andExpect(jsonPath("$.areaDenominator").value(1))
                .andExpect(jsonPath("$.areaText").value("0.000"));
    }

    @Test
    void resolveKeepsCurrentWithoutNewVersionAndReplays() throws Exception {
        createPoint("req-1", "pt-a", "S1", 0, 0).andExpect(status().isCreated());
        mergePoint("req-2", "pt-a", 1, "S1", 0, 10).andExpect(status().isOk());
        mergePoint("req-3", "pt-a", 1, "S1", 0, 8).andExpect(status().isConflict());

        // 选择 CURRENT：结果与当前一致，不加版本
        resolve("req-4", "res-1", "pt-a", 1, 2, "S1", 0, 8, selection("zMm", "CURRENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.zMm").value(10));
        // 同参重放返回同一结果
        resolve("req-4", "res-1", "pt-a", 1, 2, "S1", 0, 8, selection("zMm", "CURRENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同 resolutionId 改参：409
        resolve("req-5", "res-1", "pt-a", 1, 2, "S1", 0, 9, selection("zMm", "CURRENT"))
                .andExpect(status().isConflict());
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cross_section_version WHERE observation_id = 'pt-a'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(versionRows).isEqualTo(2);
    }

    @Test
    void resultFrozenAreaIsNotRecomputedAfterSourceChanges() throws Exception {
        createPoint("req-1", "pt-a", "S1", 0, 0).andExpect(status().isCreated());
        createPoint("req-2", "pt-b", "S1", 10, 10).andExpect(status().isCreated());
        createResult("req-3", "result-1", 5, List.of(ref("pt-a", 1), ref("pt-b", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaNumerator").value(25))
                .andExpect(jsonPath("$.areaDenominator").value(2));

        // 源点随后修改（高程变化会改变若重算时的面积）：历史成果冻结不变
        mergePoint("req-4", "pt-a", 1, "S1", 0, 10).andExpect(status().isOk());
        mockMvc.perform(get("/api/cross-sections/results/result-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaNumerator").value(25))
                .andExpect(jsonPath("$.areaDenominator").value(2))
                .andExpect(jsonPath("$.areaText").value("12.500"))
                .andExpect(jsonPath("$.points[0].zMm").value(0))
                .andExpect(jsonPath("$.points[0].sourceVersion").value(1))
                .andExpect(jsonPath("$.sourceUpdated").value(true));

        // 冻结点行仍记录旧高程，证明横向与高程取自同一提交版本，未与新版本拼接
        Integer frozenZ = jdbcTemplate.queryForObject(
                "SELECT z_mm FROM cross_section_result_point WHERE result_id = 'result-1' AND ordinal = 1",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(frozenZ).isZero();
    }
}
