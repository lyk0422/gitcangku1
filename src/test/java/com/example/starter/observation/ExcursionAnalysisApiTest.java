package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 持续越限区间分析 API 测试：区间判定主流程（含题干合成场景）、失败分支、冻结与源更新标记、幂等。
 * 使用真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExcursionAnalysisApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM excursion_analysis");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM minute_observation_version");
        jdbcTemplate.update("DELETE FROM minute_observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
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
        mockMvc.perform(post("/api/minute-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private ResultActions analyze(String requestId, List<Map<String, Object>> refs,
                                  String threshold, int consecutiveMinutes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "analyst");
        body.put("references", refs);
        body.put("threshold", threshold);
        body.put("consecutiveMinutes", consecutiveMinutes);
        return mockMvc.perform(post("/api/excursion-analyses")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> ref(String observationId, int version) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("observationId", observationId);
        ref.put("version", version);
        return ref;
    }

    private static List<Map<String, Object>> refsOf(String... idAndVersionPairs) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String pair : idAndVersionPairs) {
            String[] parts = pair.split("@");
            refs.add(ref(parts[0], Integer.parseInt(parts[1])));
        }
        return refs;
    }

    private int analysisCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM excursion_analysis", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 区间判定主流程 ----------

    @Test
    void syntheticScenarioGapBreaksRunThenFilledFormsInterval() throws Exception {
        // 题干合成场景：N=3、H=10，10:00/10:01/10:03 值 11，缺 10:02，10:04 值 10
        createObs("req-s1", "mo-100", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-s2", "mo-101", "ST-A", "2026-10-07T10:01:00Z", "11");
        createObs("req-s3", "mo-103", "ST-A", "2026-10-07T10:03:00Z", "11");
        createObs("req-s4", "mo-104", "ST-A", "2026-10-07T10:04:00Z", "10");

        // 缺 10:02：彼此断开的超限读数不能凑成一次告警
        analyze("req-a1", refsOf("mo-100@1", "mo-101@1", "mo-103@1", "mo-104@1"), "10", 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stationId").value("ST-A"))
                .andExpect(jsonPath("$.threshold").value("10"))
                .andExpect(jsonPath("$.consecutiveMinutes").value(3))
                .andExpect(jsonPath("$.intervals").isArray())
                .andExpect(jsonPath("$.intervals.length()").value(0))
                .andExpect(jsonPath("$.sourceUpdated").value(false))
                .andExpect(jsonPath("$.createdAtUtc").value("2026-10-07T12:00:00Z"));

        // 补 10:02 后形成 [10:00, 10:04)：10:04 值 10 等于阈值不算越限，区间在此结束
        createObs("req-s5", "mo-102", "ST-A", "2026-10-07T10:02:00Z", "11");
        analyze("req-a2", refsOf("mo-100@1", "mo-101@1", "mo-102@1", "mo-103@1", "mo-104@1"), "10", 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(1))
                .andExpect(jsonPath("$.intervals[0].startUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].endUtc").value("2026-10-07T10:04:00Z"))
                // 引用集合按时刻升序冻结返回
                .andExpect(jsonPath("$.references[0].observationId").value("mo-100"))
                .andExpect(jsonPath("$.references[4].observationId").value("mo-104"));
        assertThat(analysisCount()).isEqualTo(2);
    }

    @Test
    void thresholdEqualDoesNotCountAsExceedance() throws Exception {
        createObs("req-e1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "10");
        createObs("req-e2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "10.0");
        createObs("req-e3", "mo-3", "ST-A", "2026-10-07T10:02:00Z", "10.00");
        analyze("req-a3", refsOf("mo-1@1", "mo-2@1", "mo-3@1"), "10", 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(0));
    }

    @Test
    void segmentShorterThanNProducesNoInterval() throws Exception {
        createObs("req-n1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-n2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "12");
        // 连续超限只有 2 分钟，未达到 N=3
        analyze("req-a4", refsOf("mo-1@1", "mo-2@1"), "10", 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(0));
    }

    @Test
    void multipleSeparatedRunsProduceMultipleIntervals() throws Exception {
        createObs("req-r1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-r2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        createObs("req-r3", "mo-3", "ST-A", "2026-10-07T10:02:00Z", "5");
        createObs("req-r4", "mo-4", "ST-A", "2026-10-07T10:03:00Z", "12");
        createObs("req-r5", "mo-5", "ST-A", "2026-10-07T10:04:00Z", "13");
        // 只引用集合内读数：两段超限被非超限读数隔开，各自成区间
        analyze("req-a5", refsOf("mo-1@1", "mo-2@1", "mo-3@1", "mo-4@1", "mo-5@1"), "10", 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(2))
                .andExpect(jsonPath("$.intervals[0].startUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].endUtc").value("2026-10-07T10:02:00Z"))
                .andExpect(jsonPath("$.intervals[1].startUtc").value("2026-10-07T10:03:00Z"))
                .andExpect(jsonPath("$.intervals[1].endUtc").value("2026-10-07T10:05:00Z"));
    }

    // ---------- 失败分支 ----------

    @Test
    void tombstoneReferenceReturns422AndStaleVersionReturns409() throws Exception {
        createObs("req-t1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-t2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        // 删除 mo-2：当前版本变为墓碑版本 2
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-t3");
        deleteBody.put("operator", "tester");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/minute-observations/mo-2/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        // 引用墓碑当前版本：422，整次不写分析
        analyze("req-a6", refsOf("mo-1@1", "mo-2@2"), "10", 2)
                .andExpect(status().isUnprocessableEntity());
        // 引用已非当前版本：409，整次不写分析
        analyze("req-a7", refsOf("mo-1@1", "mo-2@1"), "10", 2)
                .andExpect(status().isConflict());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void staleVersionAfterMergeReturns409() throws Exception {
        createObs("req-v1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-v2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        // mo-1 被修订到版本 2：引用版本 1 已非当前版本
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-v3");
        mergeBody.put("operator", "tester");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("stationId", "ST-A");
        mergeBody.put("minuteUtc", "2026-10-07T10:00:00Z");
        mergeBody.put("reading", "12");
        mockMvc.perform(post("/api/minute-observations/mo-1/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());
        analyze("req-a8", refsOf("mo-1@1", "mo-2@1"), "10", 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(analysisCount()).isZero();
    }

    @Test
    void missingReferenceReturns404() throws Exception {
        createObs("req-x1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        analyze("req-a9", refsOf("mo-1@1", "mo-404@1"), "10", 2)
                .andExpect(status().isNotFound());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void differentStationsReturn400() throws Exception {
        createObs("req-d1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-d2", "mo-2", "ST-B", "2026-10-07T10:01:00Z", "11");
        analyze("req-a10", refsOf("mo-1@1", "mo-2@1"), "10", 2)
                .andExpect(status().isBadRequest());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void invalidNOrTooManyRefsOrDuplicateRefReturn400() throws Exception {
        createObs("req-b1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-b2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        // N 超出 2 至 5
        analyze("req-a11", refsOf("mo-1@1", "mo-2@1"), "10", 1).andExpect(status().isBadRequest());
        analyze("req-a12", refsOf("mo-1@1", "mo-2@1"), "10", 6).andExpect(status().isBadRequest());
        // 重复引用同一观测
        analyze("req-a13", refsOf("mo-1@1", "mo-1@1"), "10", 2).andExpect(status().isBadRequest());
        // 引用超过 20 条
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(ref("mo-x" + i, 1));
        }
        analyze("req-a14", tooMany, "10", 2).andExpect(status().isBadRequest());
        assertThat(analysisCount()).isZero();
    }

    @Test
    void getMissingAnalysisReturns404() throws Exception {
        mockMvc.perform(get("/api/excursion-analyses/{id}", "no-such-analysis"))
                .andExpect(status().isNotFound());
    }

    // ---------- 冻结与源更新 ----------

    @Test
    void analysisFrozenAfterSourceCorrectionAndMarkedSourceUpdated() throws Exception {
        createObs("req-f1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-f2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        createObs("req-f3", "mo-3", "ST-A", "2026-10-07T10:02:00Z", "11");
        String analysisId = objectMapper.readTree(analyze("req-a15", refsOf("mo-1@1", "mo-2@1", "mo-3@1"), "10", 3)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.intervals.length()").value(1))
                        .andReturn().getResponse().getContentAsString())
                .get("analysisId").asText();

        // 后续更正：mo-3 读数下修为 5（不再超限），只影响新分析
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-f4");
        mergeBody.put("operator", "tester");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("stationId", "ST-A");
        mergeBody.put("minuteUtc", "2026-10-07T10:02:00Z");
        mergeBody.put("reading", "5");
        mockMvc.perform(post("/api/minute-observations/mo-3/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 旧分析仍可查：区间冻结不变，并标明已有源更新
        mockMvc.perform(get("/api/excursion-analyses/{id}", analysisId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceUpdated").value(true))
                .andExpect(jsonPath("$.intervals.length()").value(1))
                .andExpect(jsonPath("$.intervals[0].startUtc").value("2026-10-07T10:00:00Z"))
                .andExpect(jsonPath("$.intervals[0].endUtc").value("2026-10-07T10:03:00Z"))
                .andExpect(jsonPath("$.references[2].reading").value("11"));

        // 原分析请求重放：返回首次完整响应，不返新告警（仍按冻结引用给出原区间）
        analyze("req-a15", refsOf("mo-1@1", "mo-2@1", "mo-3@1"), "10", 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.intervals.length()").value(1));

        // 新分析按更正后的当前版本重新判定：mo-3 不再超限，区间消失
        analyze("req-a16", refsOf("mo-1@1", "mo-2@1", "mo-3@2"), "10", 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(0));
        assertThat(analysisCount()).isEqualTo(2);
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdSameParamsReplaysFirstResponse() throws Exception {
        createObs("req-i1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-i2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        String analysisId = objectMapper.readTree(analyze("req-a17", refsOf("mo-1@1", "mo-2@1"), "10", 2)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("analysisId").asText();
        // 同参重放（阈值 10.0 与 10 按数值相等、引用顺序不同视为同参）：返回首次完整响应
        analyze("req-a17", refsOf("mo-2@1", "mo-1@1"), "10.0", 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisId").value(analysisId))
                .andExpect(jsonPath("$.intervals.length()").value(1));
        assertThat(analysisCount()).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        createObs("req-j1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-j2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        analyze("req-a18", refsOf("mo-1@1", "mo-2@1"), "10", 2).andExpect(status().isCreated());
        // 异参：阈值不同、N 不同均 409
        analyze("req-a18", refsOf("mo-1@1", "mo-2@1"), "11", 2).andExpect(status().isConflict());
        analyze("req-a18", refsOf("mo-1@1", "mo-2@1"), "10", 3).andExpect(status().isConflict());
        assertThat(analysisCount()).isEqualTo(1);
    }

    @Test
    void failedAnalysisDoesNotOccupyRequestId() throws Exception {
        createObs("req-k1", "mo-1", "ST-A", "2026-10-07T10:00:00Z", "11");
        createObs("req-k2", "mo-2", "ST-A", "2026-10-07T10:01:00Z", "11");
        // 失败（引用缺失）：不占键
        analyze("req-a19", refsOf("mo-1@1", "mo-404@1"), "10", 2).andExpect(status().isNotFound());
        // 同一 requestId 换上合法参数后应正常执行
        analyze("req-a19", refsOf("mo-1@1", "mo-2@1"), "10", 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervals.length()").value(1));
        assertThat(analysisCount()).isEqualTo(1);
    }
}
