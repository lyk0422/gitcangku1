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
 * 断面成果 API 测试：精确面积、源版本核验（422/409/404）、幂等与源更新标记
 * （真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SectionResultApiTest {

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

    private void createSection(String requestId, String observationId,
                               String sectionNo, int lateralMm, int elevationMm) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("observationType", "SECTION");
        body.put("sectionNo", sectionNo);
        body.put("lateralMm", lateralMm);
        body.put("elevationMm", elevationMm);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void createField(String requestId, String observationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void deleteObservation(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void mergeSection(String requestId, String observationId, int baseVersion,
                              String sectionNo, int lateralMm, int elevationMm) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("sectionNo", sectionNo);
        body.put("lateralMm", lateralMm);
        body.put("elevationMm", elevationMm);
        mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private ResultActions createResult(String requestId, String resultId, String operator,
                                       long waterLevelMm, String[][] sources) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resultId", resultId);
        body.put("operator", operator);
        body.put("waterLevelMm", waterLevelMm);
        List<Map<String, Object>> sourceList = new ArrayList<>();
        for (String[] source : sources) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("observationId", source[0]);
            ref.put("expectedVersion", Integer.parseInt(source[1]));
            sourceList.add(ref);
        }
        body.put("sources", sourceList);
        return mockMvc.perform(post("/api/section-results")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getResult(String resultId) throws Exception {
        return mockMvc.perform(get("/api/section-results/{id}", resultId));
    }

    private int resultCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM section_result", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 成功主流程 ----------

    @Test
    void syntheticSectionYieldsExactHalfTriangle() throws Exception {
        // 合成场景：横向 0 与 10 毫米、高程 0 与 10 毫米、水面 5 毫米，只有前半三角形有水
        createSection("req-p1", "sec-a", "S1", 0, 0);
        createSection("req-p2", "sec-b", "S1", 10, 10);
        createResult("req-r1", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resultId").value("result-1"))
                .andExpect(jsonPath("$.sectionNo").value("S1"))
                .andExpect(jsonPath("$.waterLevelMm").value(5))
                .andExpect(jsonPath("$.areaExact").value("25/2"))
                .andExpect(jsonPath("$.areaDisplay").value("12.500"))
                .andExpect(jsonPath("$.sourceUpdated").value(false))
                .andExpect(jsonPath("$.points[0].observationId").value("sec-a"))
                .andExpect(jsonPath("$.points[0].lateralMm").value(0))
                .andExpect(jsonPath("$.points[1].lateralMm").value(10));
        assertThat(resultCount()).isEqualTo(1);
        // 持久化精确分数：分子 25、分母 2
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT area_numerator, area_denominator, point_count FROM section_result "
                        + "WHERE result_id = 'result-1'");
        assertThat(row.get("AREA_NUMERATOR")).isEqualTo("25");
        assertThat(row.get("AREA_DENOMINATOR")).isEqualTo("2");
        assertThat(((Number) row.get("POINT_COUNT")).intValue()).isEqualTo(2);
    }

    @Test
    void fullySubmergedTrapezoidAndDisplayRounding() throws Exception {
        createSection("req-p3", "sec-a", "S1", 0, 0);
        createSection("req-p4", "sec-b", "S1", 10, 0);
        // 整段梯形 50：验证不是 0 也不是错误口径
        createResult("req-r2", "result-2", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaExact").value("50"))
                .andExpect(jsonPath("$.areaDisplay").value("50.000"));
    }

    @Test
    void tenSourcesAccepted() throws Exception {
        String[][] sources = new String[10][2];
        for (int i = 0; i < 10; i++) {
            createSection("req-t" + i, "sec-" + i, "S1", i * 10, 0);
            sources[i] = new String[]{"sec-" + i, "1"};
        }
        createResult("req-r3", "result-3", "operator-a", 5, sources)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaExact").value("450"))
                .andExpect(jsonPath("$.points.length()").value(10));
    }

    // ---------- 失败分支：422 / 409 / 404 / 400 ----------

    @Test
    void tombstoneSourceReturns422() throws Exception {
        createSection("req-p5", "sec-a", "S1", 0, 0);
        createSection("req-p6", "sec-b", "S1", 10, 10);
        deleteObservation("req-p7", "sec-b", 1);
        createResult("req-r4", "result-4", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "2"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(resultCount()).isZero();
    }

    @Test
    void fieldTypeSourceReturns422() throws Exception {
        createSection("req-p8", "sec-a", "S1", 0, 0);
        createField("req-p9", "obs-field");
        // FIELD 观测缺少断面字段：缺字段 422
        createResult("req-r5", "result-5", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"obs-field", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(resultCount()).isZero();
    }

    @Test
    void differentSectionsReturn422() throws Exception {
        createSection("req-p10", "sec-a", "S1", 0, 0);
        createSection("req-p11", "sec-b", "S2", 10, 10);
        // 源点断面不一致：未解决冲突 422
        createResult("req-r6", "result-6", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(resultCount()).isZero();
    }

    @Test
    void nonIncreasingLateralReturns422AndIsNotSorted() throws Exception {
        createSection("req-p12", "sec-a", "S1", 0, 0);
        createSection("req-p13", "sec-b", "S1", 10, 10);
        // 请求顺序横向递减：不得排序后接受，直接 422
        createResult("req-r7", "result-7", "operator-a", 5,
                new String[][]{{"sec-b", "1"}, {"sec-a", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(resultCount()).isZero();
    }

    @Test
    void duplicateLateralReturns422() throws Exception {
        createSection("req-p14", "sec-a", "S1", 0, 0);
        createSection("req-p15", "sec-b", "S1", 0, 5);
        createResult("req-r8", "result-8", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(resultCount()).isZero();
    }

    @Test
    void duplicateSourceReferenceReturns422() throws Exception {
        createSection("req-p16", "sec-a", "S1", 0, 0);
        createResult("req-r9", "result-9", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-a", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(resultCount()).isZero();
    }

    @Test
    void staleSourceVersionReturns409() throws Exception {
        createSection("req-p17", "sec-a", "S1", 0, 0);
        createSection("req-p18", "sec-b", "S1", 10, 10);
        mergeSection("req-p19", "sec-b", 1, "S1", 10, 20);
        // 旧版 409：sec-b 当前版本已是 2
        createResult("req-r10", "result-10", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(resultCount()).isZero();
    }

    @Test
    void missingSourceReturns404() throws Exception {
        createSection("req-p20", "sec-a", "S1", 0, 0);
        createResult("req-r11", "result-11", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-x", "1"}})
                .andExpect(status().isNotFound());
        assertThat(resultCount()).isZero();
    }

    @Test
    void singleSourceReturns400() throws Exception {
        createSection("req-p21", "sec-a", "S1", 0, 0);
        createResult("req-r12", "result-12", "operator-a", 5,
                new String[][]{{"sec-a", "1"}})
                .andExpect(status().isBadRequest());
        assertThat(resultCount()).isZero();
    }

    // ---------- 幂等与去重 ----------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() throws Exception {
        createSection("req-i1", "sec-a", "S1", 0, 0);
        createSection("req-i2", "sec-b", "S1", 10, 10);
        createResult("req-i3", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次完整响应，不新增成果
        createResult("req-i3", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaExact").value("25/2"));
        assertThat(resultCount()).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-i3'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createSection("req-i4", "sec-a", "S1", 0, 0);
        createSection("req-i5", "sec-b", "S1", 10, 10);
        createResult("req-i6", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated());
        // 同键异参（H 不同）：409
        createResult("req-i6", "result-2", "operator-a", 7,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isConflict());
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void resultIdReusedWithDifferentParamsReturns409() throws Exception {
        createSection("req-i7", "sec-a", "S1", 0, 0);
        createSection("req-i8", "sec-b", "S1", 10, 10);
        createResult("req-i9", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated());
        // 不同 requestId、相同 resultId、异参：409
        createResult("req-i10", "result-1", "operator-a", 8,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isConflict());
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void resultIdReusedWithSameParamsReturnsOriginalResult() throws Exception {
        createSection("req-i11", "sec-a", "S1", 0, 0);
        createSection("req-i12", "sec-b", "S1", 10, 10);
        createResult("req-i13", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated());
        // 不同 requestId、相同 resultId、同参：返回原成果
        createResult("req-i14", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultId").value("result-1"))
                .andExpect(jsonPath("$.areaExact").value("25/2"));
        assertThat(resultCount()).isEqualTo(1);
    }

    @Test
    void failedRequestDoesNotOccupyRequestId() throws Exception {
        createSection("req-i15", "sec-a", "S1", 0, 0);
        createSection("req-i16", "sec-b", "S1", 10, 10);
        // 先失败（横向非递增 422）：不占键
        createResult("req-i17", "result-1", "operator-a", 5,
                new String[][]{{"sec-b", "1"}, {"sec-a", "1"}})
                .andExpect(status().isUnprocessableEntity());
        // 同一 requestId 修正参数后正常执行
        createResult("req-i17", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.areaExact").value("25/2"));
        assertThat(resultCount()).isEqualTo(1);
    }

    // ---------- 冻结与源更新 ----------

    @Test
    void laterSourceChangeMarksSourceUpdatedWithoutRecompute() throws Exception {
        createSection("req-f1", "sec-a", "S1", 0, 0);
        createSection("req-f2", "sec-b", "S1", 10, 10);
        createResult("req-f3", "result-1", "operator-a", 5,
                new String[][]{{"sec-a", "1"}, {"sec-b", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceUpdated").value(false));
        // 源观测之后变更：历史成果显示源已更新，但面积不重算
        mergeSection("req-f4", "sec-b", 1, "S1", 10, 20);
        getResult("result-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceUpdated").value(true))
                .andExpect(jsonPath("$.areaExact").value("25/2"))
                .andExpect(jsonPath("$.areaDisplay").value("12.500"))
                .andExpect(jsonPath("$.points[1].elevationMm").value(10))
                .andExpect(jsonPath("$.points[1].version").value(1));
    }

    @Test
    void getMissingResultReturns404() throws Exception {
        getResult("result-x").andExpect(status().isNotFound());
    }
}
