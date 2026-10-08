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
 * 圆周区间裁定 API 测试：题干合成样例、无交集 422 不留裁定、版本/墓碑/类型前置条件、
 * 冻结与幂等边界（真实 H2 内存库与固定 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArcAdjudicationApiTest {

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
        jdbcTemplate.update("DELETE FROM arc_adjudication");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions createArc(String requestId, String observationId,
                                    int startAngle, int arcLength) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("startAngle", startAngle);
        body.put("arcLength", arcLength);
        body.put("operator", "operator-setup");
        return mockMvc.perform(post("/api/observations/arcs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeArc(String requestId, String observationId, int baseVersion,
                                   int startAngle, int arcLength) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("startAngle", startAngle);
        body.put("arcLength", arcLength);
        body.put("operator", "operator-setup");
        return mockMvc.perform(post("/api/observations/arcs/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adjudicate(String requestId, String adjudicationId, String operator,
                                     List<int[]> references) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("adjudicationId", adjudicationId);
        body.put("operator", operator);
        List<Map<String, Object>> refs = new ArrayList<>();
        for (int[] reference : references) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("observationId", "arc-" + reference[0]);
            ref.put("version", reference[1]);
            refs.add(ref);
        }
        body.put("references", refs);
        return mockMvc.perform(post("/api/observations/arcs/adjudications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getAdjudication(String adjudicationId) throws Exception {
        return mockMvc.perform(get("/api/observations/arcs/adjudications/{id}", adjudicationId));
    }

    private int adjudicationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM arc_adjudication", Integer.class);
        return count == null ? 0 : count;
    }

    // ---------- 题干合成样例 ----------

    @Test
    void specScenarioIntersectionCrossesZeroThenEmpty() throws Exception {
        createArc("req-s1", "arc-1", 350000, 20000).andExpect(status().isCreated());
        createArc("req-s2", "arc-2", 355000, 10000).andExpect(status().isCreated());

        adjudicate("req-s3", "adj-1", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$.operator").value("operator-a"))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.components[0].startAngle").value(355000))
                .andExpect(jsonPath("$.components[0].arcLength").value(10000))
                .andExpect(jsonPath("$.sources.length()").value(2))
                .andExpect(jsonPath("$.sources[0].observationId").value("arc-1"))
                .andExpect(jsonPath("$.sources[0].startAngle").value(350000))
                .andExpect(jsonPath("$.sources[1].observationId").value("arc-2"))
                .andExpect(jsonPath("$.createdAtUtc").value("2026-10-07T12:00:00Z"));

        // 再与起始 20000 长度 1000 相交：无交集 422，且不留裁定
        createArc("req-s4", "arc-3", 20000, 1000).andExpect(status().isCreated());
        adjudicate("req-s5", "adj-2", "operator-a",
                List.of(new int[]{1, 1}, new int[]{2, 1}, new int[]{3, 1}))
                .andExpect(status().isUnprocessableEntity());
        assertThat(adjudicationCount()).isEqualTo(1);
        getAdjudication("adj-2").andExpect(status().isNotFound());
        // 失败不占键：同一 requestId 换合法参数可正常执行
        adjudicate("req-s5", "adj-2", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components[0].startAngle").value(355000));
        assertThat(adjudicationCount()).isEqualTo(2);
    }

    @Test
    void degenerateSinglePointIntersectionAllowed() throws Exception {
        createArc("req-p1", "arc-1", 1000, 1000).andExpect(status().isCreated());
        createArc("req-p2", "arc-2", 2000, 1000).andExpect(status().isCreated());
        adjudicate("req-p3", "adj-p", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components[0].startAngle").value(2000))
                .andExpect(jsonPath("$.components[0].arcLength").value(0));
    }

    // ---------- 前置条件 ----------

    @Test
    void referenceCountOutOfBoundsReturns400() throws Exception {
        createArc("req-b1", "arc-1", 1000, 1000).andExpect(status().isCreated());
        adjudicate("req-b2", "adj-b1", "operator-a", List.of(new int[]{1, 1}))
                .andExpect(status().isBadRequest());
        List<int[]> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(new int[]{1, 1});
        }
        adjudicate("req-b3", "adj-b2", "operator-a", eleven).andExpect(status().isBadRequest());
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void blankOperatorReturns400() throws Exception {
        createArc("req-b4", "arc-1", 1000, 1000).andExpect(status().isCreated());
        createArc("req-b5", "arc-2", 1000, 1000).andExpect(status().isCreated());
        adjudicate("req-b6", "adj-b3", " ", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staleVersionReturns409AndCurrentVersionWins() throws Exception {
        createArc("req-v1", "arc-1", 1000, 1000).andExpect(status().isCreated());
        createArc("req-v2", "arc-2", 500, 1000).andExpect(status().isCreated());
        mergeArc("req-v3", "arc-1", 1, 1200, 800).andExpect(status().isOk());
        // 版本不是当前：409
        adjudicate("req-v4", "adj-v1", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(adjudicationCount()).isZero();
        // 使用当前版本：成功且冻结新版本圆弧
        adjudicate("req-v5", "adj-v1", "operator-a", List.of(new int[]{1, 2}, new int[]{2, 1}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sources[0].version").value(2))
                .andExpect(jsonPath("$.sources[0].startAngle").value(1200));
    }

    @Test
    void tombstoneReferenceReturns422() throws Exception {
        createArc("req-t1", "arc-1", 1000, 1000).andExpect(status().isCreated());
        createArc("req-t2", "arc-2", 500, 1000).andExpect(status().isCreated());
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-t3");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/{id}/delete", "arc-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        adjudicate("req-t4", "adj-t1", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 2}))
                .andExpect(status().isUnprocessableEntity());
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void missingOrPlainOrDuplicateReferenceRejected() throws Exception {
        createArc("req-x1", "arc-1", 1000, 1000).andExpect(status().isCreated());
        createArc("req-x2", "arc-2", 500, 1000).andExpect(status().isCreated());
        // 引用不存在的观测：404
        adjudicate("req-x3", "adj-x1", "operator-a", List.of(new int[]{1, 1}, new int[]{9, 1}))
                .andExpect(status().isNotFound());
        // 引用普通观测：422
        Map<String, Object> plainBody = new LinkedHashMap<>();
        plainBody.put("requestId", "req-x4");
        plainBody.put("observationId", "arc-5");
        plainBody.put("location", "站点A");
        plainBody.put("reading", "1.0");
        plainBody.put("note", "备注");
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(plainBody)))
                .andExpect(status().isCreated());
        adjudicate("req-x5", "adj-x2", "operator-a", List.of(new int[]{1, 1}, new int[]{5, 1}))
                .andExpect(status().isUnprocessableEntity());
        // 同一观测重复引用（未解决冲突）：422
        adjudicate("req-x6", "adj-x3", "operator-a", List.of(new int[]{1, 1}, new int[]{1, 1}))
                .andExpect(status().isUnprocessableEntity());
        assertThat(adjudicationCount()).isZero();
    }

    // ---------- 幂等与冻结 ----------

    @Test
    void sameRequestReplaysAndReferenceOrderDoesNotMatter() throws Exception {
        createArc("req-r1", "arc-1", 350000, 20000).andExpect(status().isCreated());
        createArc("req-r2", "arc-2", 355000, 10000).andExpect(status().isCreated());

        adjudicate("req-r3", "adj-r1", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isCreated());
        // 同 requestId 同参（引用顺序不同）：回首次完整结果，不新增裁定
        adjudicate("req-r3", "adj-r1", "operator-a", List.of(new int[]{2, 1}, new int[]{1, 1}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components[0].startAngle").value(355000));
        // 同 adjudicationId 同参（不同 requestId、引用顺序不同）：重放原裁定
        adjudicate("req-r4", "adj-r1", "operator-a", List.of(new int[]{2, 1}, new int[]{1, 1}))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjudicationId").value("adj-r1"))
                .andExpect(jsonPath("$.components[0].arcLength").value(10000));
        assertThat(adjudicationCount()).isEqualTo(1);
        // 同 requestId 改参：409
        adjudicate("req-r3", "adj-r9", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isConflict());
        // 同 adjudicationId 改参（操作者不同）：409
        adjudicate("req-r5", "adj-r1", "operator-b", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isConflict());
        assertThat(adjudicationCount()).isEqualTo(1);
    }

    @Test
    void adjudicationFrozenAfterLaterRevisions() throws Exception {
        createArc("req-f1", "arc-1", 350000, 20000).andExpect(status().isCreated());
        createArc("req-f2", "arc-2", 355000, 10000).andExpect(status().isCreated());
        adjudicate("req-f3", "adj-f1", "operator-a", List.of(new int[]{1, 1}, new int[]{2, 1}))
                .andExpect(status().isCreated());
        // 后续修订：arc-1 前进到版本 2
        mergeArc("req-f4", "arc-1", 1, 300000, 5000).andExpect(status().isOk());
        // 旧裁定不倒改：仍冻结原圆弧与交集分量
        getAdjudication("adj-f1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].observationId").value("arc-1"))
                .andExpect(jsonPath("$.sources[0].version").value(1))
                .andExpect(jsonPath("$.sources[0].startAngle").value(350000))
                .andExpect(jsonPath("$.sources[0].arcLength").value(20000))
                .andExpect(jsonPath("$.components[0].startAngle").value(355000))
                .andExpect(jsonPath("$.components[0].arcLength").value(10000))
                .andExpect(jsonPath("$.createdAtUtc").value("2026-10-07T12:00:00Z"));
    }
}
