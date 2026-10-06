package com.example.starter.excursion;

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
 * 越限区间分析 API 测试：主流程（题干合成样例）、失败分支（404/422/409/400）、
 * 幂等重放与冻结语义（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExcursionAnalysisApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM excursion_analysis");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM excursion_version");
        jdbcTemplate.update("DELETE FROM excursion_current");
    }

    private void createObs(String requestId, String observationId, String stationId,
                           String minuteUtc, String reading) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "tester");
        body.put("observationId", observationId);
        body.put("stationId", stationId);
        body.put("minuteUtc", minuteUtc);
        body.put("reading", reading);
        mockMvc.perform(post("/api/excursions/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private ResultActions analyze(String requestId, String threshold, int n,
                                  List<Map<String, Object>> references) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "analyst");
        body.put("threshold", threshold);
        body.put("consecutiveMinutes", n);
        body.put("references", references);
        return mockMvc.perform(post("/api/excursions/analyses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<Map<String, Object>> refs(String... idAndVersionPairs) {
        List<Map<String, Object>> references = new ArrayList<>();
        for (int i = 0; i < idAndVersionPairs.length; i += 2) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("observationId", idAndVersionPairs[i]);
            ref.put("version", Integer.parseInt(idAndVersionPairs[i + 1]));
            references.add(ref);
        }
        return references;
    }

    private int analysisCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM excursion_analysis", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：题干合成样例 ----------

    @Test
    void syntheticScenarioGapThenFilled() throws Exception {
        // N=3、H=10：10:00、10:01、10:03 值都 11，缺 10:02 不成区间
        createObs("req-s1", "obs-1000", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-s2", "obs-1001", "ST-1", "2026-10-07T10:01:00Z", "11");
        createObs("req-s3", "obs-1003", "ST-1", "2026-10-07T10:03:00Z", "11");

        analyze("req-a1", "10", 3, refs("obs-1000", "1", "obs-1001", "1", "obs-1003", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationId").value("ST-1"))
                .andExpect(jsonPath("$.threshold").value("10"))
                .andExpect(jsonPath("$.consecutiveMinutes").value(3))
                .andExpect(jsonPath("$.intervals").isArray())
                .andExpect(jsonPath("$.intervals.length()").value(0))
                .andExpect(jsonPath("$.sourceUpdated").value(false));

        // 补 10:02 后形成 [10:00, 10:04)；10:04 值 10 不超限，区间到此结束
        createObs("req-s4", "obs-1002", "ST-1", "2026-10-07T10:02:00Z", "11");
        createObs("req-s5", "obs-1004", "ST-1", "2026-10-07T10:04:00Z", "10");

        analyze("req-a2", "10", 3,
                refs("obs-1000", "1", "obs-1001", "1", "obs-1002", "1", "obs-1003", "1", "obs-1004", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(1))
                .andExpect(jsonPath("$.intervals[0].startUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].endUtc").value("2026-10-07T10:04:00Z"))
                .andExpect(jsonPath("$.references.length()").value(5))
                .andExpect(jsonPath("$.references[0].observationId").value("obs-1000"))
                .andExpect(jsonPath("$.references[4].minuteUtc").value("2026-10-07T10:04:00Z"));
        assertThat(analysisCount()).isEqualTo(2);
    }

    @Test
    void thresholdEqualityProducesNoInterval() throws Exception {
        createObs("req-t1", "obs-e0", "ST-1", "2026-10-07T10:00:00Z", "10");
        createObs("req-t2", "obs-e1", "ST-1", "2026-10-07T10:01:00Z", "10.000");
        createObs("req-t3", "obs-e2", "ST-1", "2026-10-07T10:02:00Z", "10");
        analyze("req-a3", "10", 2, refs("obs-e0", "1", "obs-e1", "1", "obs-e2", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(0));
    }

    // ---------- 失败分支：整次不写分析 ----------

    @Test
    void missingObservationReturns404() throws Exception {
        createObs("req-n1", "obs-m0", "ST-1", "2026-10-07T10:00:00Z", "11");
        analyze("req-a4", "10", 2, refs("obs-m0", "1", "obs-missing", "1"))
                .andExpect(status().isNotFound());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void tombstoneReferenceReturns422() throws Exception {
        createObs("req-b1", "obs-t0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-b2", "obs-t1", "ST-1", "2026-10-07T10:01:00Z", "11");
        // 删除 obs-t1（版本 2 为墓碑）
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-b3");
        deleteBody.put("operator", "tester");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/excursions/observations/{id}/delete", "obs-t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        analyze("req-a5", "10", 2, refs("obs-t0", "1", "obs-t1", "2"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void staleReferenceVersionReturns409() throws Exception {
        createObs("req-v1", "obs-s0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-v2", "obs-s1", "ST-1", "2026-10-07T10:01:00Z", "11");
        // obs-s1 前进到版本 2
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-v3");
        mergeBody.put("operator", "tester");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("stationId", "ST-1");
        mergeBody.put("minuteUtc", "2026-10-07T10:01:00Z");
        mergeBody.put("reading", "12");
        mockMvc.perform(post("/api/excursions/observations/{id}/merge", "obs-s1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 引用版本 1 已非当前版本：409，整次不写分析
        analyze("req-a6", "10", 2, refs("obs-s0", "1", "obs-s1", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(analysisCount()).isZero();
    }

    @Test
    void differentStationsReturn400() throws Exception {
        createObs("req-g1", "obs-d0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-g2", "obs-d1", "ST-2", "2026-10-07T10:01:00Z", "11");
        analyze("req-a7", "10", 2, refs("obs-d0", "1", "obs-d1", "1"))
                .andExpect(status().isBadRequest());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void invalidShapeReturns400() throws Exception {
        createObs("req-h1", "obs-p0", "ST-1", "2026-10-07T10:00:00Z", "11");
        // N 超出 2..5
        analyze("req-a8", "10", 6, refs("obs-p0", "1")).andExpect(status().isBadRequest());
        analyze("req-a9", "10", 1, refs("obs-p0", "1")).andExpect(status().isBadRequest());
        // 重复引用同一观测
        analyze("req-a10", "10", 2, refs("obs-p0", "1", "obs-p0", "1"))
                .andExpect(status().isBadRequest());
        // 超过 20 条引用
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("observationId", "obs-x" + i);
            ref.put("version", 1);
            tooMany.add(ref);
        }
        analyze("req-a11", "10", 2, tooMany).andExpect(status().isBadRequest());
        assertThat(analysisCount()).isZero();
    }

    // ---------- 幂等与冻结 ----------

    @Test
    void sameRequestIdReplaysOriginalAnalysisWithoutNewAlerts() throws Exception {
        createObs("req-r1", "obs-q0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-r2", "obs-q1", "ST-1", "2026-10-07T10:01:00Z", "11");

        String analysisId = objectMapper.readTree(analyze("req-a20", "10", 2, refs("obs-q0", "1", "obs-q1", "1"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.intervals.length()").value(1))
                        .andReturn().getResponse().getContentAsString())
                .get("analysisId").asText();

        // 同参重放：返回首次完整响应（同一 analysisId、同一区间），不产生新分析
        analyze("req-a20", "10", 2, refs("obs-q0", "1", "obs-q1", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.intervals.length()").value(1))
                .andExpect(jsonPath("$.sourceUpdated").value(false));
        assertThat(analysisCount()).isEqualTo(1);

        // 后续更正（obs-q1 改为 9，不再超限）只影响新分析
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-r3");
        mergeBody.put("operator", "tester");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("stationId", "ST-1");
        mergeBody.put("minuteUtc", "2026-10-07T10:01:00Z");
        mergeBody.put("reading", "9");
        mockMvc.perform(post("/api/excursions/observations/{id}/merge", "obs-q1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 旧结果仍可查：区间不变，标明已有源更新
        mockMvc.perform(get("/api/excursions/analyses/{id}", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervals.length()").value(1))
                .andExpect(jsonPath("$.intervals[0].startUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].endUtc").value("2026-10-07T10:02:00Z"))
                .andExpect(jsonPath("$.sourceUpdated").value(true));

        // 原分析重放不返新告警：仍返回首次响应（sourceUpdated=false 的原始区间）
        analyze("req-a20", "10", 2, refs("obs-q0", "1", "obs-q1", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.intervals.length()").value(1))
                .andExpect(jsonPath("$.sourceUpdated").value(false));
        assertThat(analysisCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createObs("req-w1", "obs-z0", "ST-1", "2026-10-07T10:00:00Z", "11");
        createObs("req-w2", "obs-z1", "ST-1", "2026-10-07T10:01:00Z", "11");
        analyze("req-a30", "10", 2, refs("obs-z0", "1", "obs-z1", "1")).andExpect(status().isCreated());
        // 改阈值：异参 409
        analyze("req-a30", "11", 2, refs("obs-z0", "1", "obs-z1", "1")).andExpect(status().isConflict());
        // 改 N：异参 409
        analyze("req-a30", "10", 3, refs("obs-z0", "1", "obs-z1", "1")).andExpect(status().isConflict());
        // 改期望版本：异参 409
        analyze("req-a30", "10", 2, refs("obs-z0", "1", "obs-z1", "2")).andExpect(status().isConflict());
        // 阈值按值比较：10 与 10.0 同值，不算异参
        analyze("req-a30", "10.0", 2, refs("obs-z0", "1", "obs-z1", "1")).andExpect(status().isCreated());
        assertThat(analysisCount()).isEqualTo(1);
    }

    @Test
    void failedAnalysisDoesNotOccupyRequestId() throws Exception {
        createObs("req-y1", "obs-f0", "ST-1", "2026-10-07T10:00:00Z", "11");
        // 引用缺失观测：404 失败，不占键
        analyze("req-a40", "10", 2, refs("obs-f0", "1", "obs-f1", "1")).andExpect(status().isNotFound());
        // 同一 requestId 修正参数后正常执行
        createObs("req-y2", "obs-f1", "ST-1", "2026-10-07T10:01:00Z", "11");
        analyze("req-a40", "10", 2, refs("obs-f0", "1", "obs-f1", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(1));
        assertThat(analysisCount()).isEqualTo(1);
    }

    @Test
    void getMissingAnalysisReturns404() throws Exception {
        mockMvc.perform(get("/api/excursions/analyses/{id}", "no-such-analysis"))
                .andExpect(status().isNotFound());
    }
}
