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
 * 圆弧观测与圆周区间裁定 API 测试：主流程、失败分支、幂等与冻结语义（真实 H2 内存库，MySQL 兼容模式）。
 * 使用固定 UTC 时钟核对裁定时刻。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArcObservationApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-08T10:15:30Z");

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
        jdbcTemplate.update("DELETE FROM arc_adjudication_component");
        jdbcTemplate.update("DELETE FROM arc_adjudication_source_arc");
        jdbcTemplate.update("DELETE FROM arc_adjudication_source");
        jdbcTemplate.update("DELETE FROM arc_adjudication");
        jdbcTemplate.update("DELETE FROM arc_observation_arc");
        jdbcTemplate.update("DELETE FROM arc_observation_version");
        jdbcTemplate.update("DELETE FROM arc_observation_current");
        jdbcTemplate.update("DELETE FROM request_log");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private List<Map<String, Integer>> arcsJson(int[][] arcs) {
        List<Map<String, Integer>> list = new ArrayList<>();
        for (int[] arc : arcs) {
            Map<String, Integer> component = new LinkedHashMap<>();
            component.put("startAngle", arc[0]);
            component.put("arcLength", arc[1]);
            list.add(component);
        }
        return list;
    }

    private ResultActions createArc(String requestId, String observationId, String operator,
                                    int[][] arcs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("operator", operator);
        body.put("arcs", arcsJson(arcs));
        return mockMvc.perform(post("/api/arc-observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeArc(String requestId, String observationId, int baseVersion,
                                   String operator, int[][] arcs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("operator", operator);
        body.put("arcs", arcsJson(arcs));
        return mockMvc.perform(post("/api/arc-observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolveArc(String requestId, String observationId, int expectedCurrentVersion,
                                     String operator, int[][] arcs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("operator", operator);
        body.put("arcs", arcsJson(arcs));
        return mockMvc.perform(post("/api/arc-observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteArc(String requestId, String observationId, int expectedVersion,
                                    String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/arc-observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adjudicate(String requestId, String adjudicationId, String operator,
                                     String[][] references) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("adjudicationId", adjudicationId);
        body.put("operator", operator);
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String[] reference : references) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("observationId", reference[0]);
            ref.put("version", Integer.parseInt(reference[1]));
            refs.add(ref);
        }
        body.put("references", refs);
        return mockMvc.perform(post("/api/arc-observations/adjudications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/arc-observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/arc-observations/{id}/versions/{version}", observationId, version));
    }

    private ResultActions getAdjudication(String adjudicationId) throws Exception {
        return mockMvc.perform(get("/api/arc-observations/adjudications/{aid}", adjudicationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM arc_observation_version WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int adjudicationCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM arc_adjudication", Integer.class);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createReturnsSortedArcsAndVersionOne() throws Exception {
        createArc("req-ac1", "arc-1", "op-1", new int[][]{{100000, 5000}, {10000, 5000}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("arc-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.conflictPending").value(false))
                .andExpect(jsonPath("$.arcs[0].startAngle").value(10000))
                .andExpect(jsonPath("$.arcs[1].startAngle").value(100000));

        getCurrent("arc-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arcs[0].startAngle").value(10000))
                .andExpect(jsonPath("$.arcs[0].arcLength").value(5000));
        assertThat(versionCount("arc-1")).isEqualTo(1);
    }

    @Test
    void createWithOutOfRangeAngleReturns400() throws Exception {
        createArc("req-ac2", "arc-1", "op-1", new int[][]{{360000, 1000}})
                .andExpect(status().isBadRequest());
        createArc("req-ac3", "arc-1", "op-1", new int[][]{{-1, 1000}})
                .andExpect(status().isBadRequest());
        createArc("req-ac4", "arc-1", "op-1", new int[][]{{10000, 0}})
                .andExpect(status().isBadRequest());
        createArc("req-ac5", "arc-1", "op-1", new int[][]{{10000, 180001}})
                .andExpect(status().isBadRequest());
        assertThat(versionCount("arc-1")).isZero();
        assertThat(requestLogCount("req-ac2")).isZero();
    }

    @Test
    void createWithBlankOperatorReturns400() throws Exception {
        createArc("req-ac6", "arc-1", " ", new int[][]{{10000, 5000}})
                .andExpect(status().isBadRequest());
        assertThat(versionCount("arc-1")).isZero();
    }

    @Test
    void createDuplicateObservationIdReturns409() throws Exception {
        createArc("req-ac7", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        createArc("req-ac8", "arc-1", "op-1", new int[][]{{20000, 5000}})
                .andExpect(status().isConflict());
        assertThat(versionCount("arc-1")).isEqualTo(1);
    }

    // ---------- 三方合并（圆弧为完整字段组） ----------

    @Test
    void mergeAcceptsCandidateWhenCurrentUnchanged() throws Exception {
        createArc("req-am1", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        mergeArc("req-am2", "arc-1", 1, "op-2", new int[][]{{20000, 8000}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.arcs[0].startAngle").value(20000))
                .andExpect(jsonPath("$.arcs[0].arcLength").value(8000));
        assertThat(versionCount("arc-1")).isEqualTo(2);
        getVersion("arc-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arcs[0].startAngle").value(10000));
    }

    @Test
    void mergeTreatsArcSetAsWholeFieldGroup() throws Exception {
        createArc("req-am3", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        // 服务端前进到版本 2：弧长改为 6000
        mergeArc("req-am4", "arc-1", 1, "op-1", new int[][]{{10000, 6000}}).andExpect(status().isOk());
        // 离线端基于版本 1 把起始角改为 20000：整组冲突，绝不能把起点 20000 与弧长 6000 拼组
        mergeArc("req-am5", "arc-1", 1, "op-2", new int[][]{{20000, 5000}})
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("arcs"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        // 不写入任何部分结果，并落库未解决冲突标记
        getCurrent("arc-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.arcs[0].startAngle").value(10000))
                .andExpect(jsonPath("$.arcs[0].arcLength").value(6000))
                .andExpect(jsonPath("$.conflictPending").value(true));
        assertThat(versionCount("arc-1")).isEqualTo(2);
        // 冲突失败不占 requestId 键
        assertThat(requestLogCount("req-am5")).isZero();
    }

    @Test
    void mergeWithSameSetInDifferentOrderDoesNotAddVersion() throws Exception {
        createArc("req-am6", "arc-1", "op-1", new int[][]{{10000, 5000}, {100000, 5000}})
                .andExpect(status().isCreated());
        // 区间全集顺序可变：同一集合不同顺序视为未改，不加版本
        mergeArc("req-am7", "arc-1", 1, "op-2", new int[][]{{100000, 5000}, {10000, 5000}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("arc-1")).isEqualTo(1);
    }

    @Test
    void mergeOnDeletedObservationReturns410() throws Exception {
        createArc("req-am8", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        deleteArc("req-am9", "arc-1", 1, "op-1").andExpect(status().isOk());
        mergeArc("req-am10", "arc-1", 1, "op-1", new int[][]{{20000, 5000}})
                .andExpect(status().isGone());
    }

    // ---------- 人工裁决 ----------

    @Test
    void resolveClearsConflictAndWritesCandidate() throws Exception {
        createArc("req-ar1", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        mergeArc("req-ar2", "arc-1", 1, "op-1", new int[][]{{10000, 6000}}).andExpect(status().isOk());
        mergeArc("req-ar3", "arc-1", 1, "op-2", new int[][]{{20000, 5000}})
                .andExpect(status().isConflict());
        getCurrent("arc-1").andExpect(jsonPath("$.conflictPending").value(true));

        // 人工裁决：写入候选圆弧形集合，清除未解决冲突标记，版本前进
        resolveArc("req-ar4", "arc-1", 2, "op-3", new int[][]{{30000, 7000}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.conflictPending").value(false))
                .andExpect(jsonPath("$.arcs[0].startAngle").value(30000));
        assertThat(versionCount("arc-1")).isEqualTo(3);
    }

    @Test
    void resolveWithMismatchedVersionReturns409() throws Exception {
        createArc("req-ar5", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        resolveArc("req-ar6", "arc-1", 7, "op-1", new int[][]{{20000, 5000}})
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("arc-1").andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deleteCreatesTombstoneVersion() throws Exception {
        createArc("req-ad1", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        deleteArc("req-ad2", "arc-1", 1, "op-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.arcs").doesNotExist());
        getCurrent("arc-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.arcs").doesNotExist());
        getVersion("arc-1", 1)
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.arcs[0].startAngle").value(10000));
        deleteArc("req-ad3", "arc-1", 2, "op-1").andExpect(status().isGone());
        assertThat(versionCount("arc-1")).isEqualTo(2);
    }

    @Test
    void deleteWithMismatchedVersionReturns409() throws Exception {
        createArc("req-ad4", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        deleteArc("req-ad5", "arc-1", 9, "op-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        getCurrent("arc-1")
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    // ---------- 圆周区间裁定 ----------

    @Test
    void adjudicateComputesIntersectionAcrossZero() throws Exception {
        // 题干示例：起始 350000 弧长 20000 与起始 355000 弧长 10000，交集跨零度
        createArc("req-aj1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-aj2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());

        adjudicate("req-aj3", "adj-1", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.adjudicationId").value("adj-1"))
                .andExpect(jsonPath("$.operator").value("op-9"))
                .andExpect(jsonPath("$.adjudicatedAtUtc").value("2026-10-08T10:15:30Z"))
                .andExpect(jsonPath("$.components[0].startAngle").value(355000))
                .andExpect(jsonPath("$.components[0].arcLength").value(10000))
                .andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.sources[0].observationId").value("arc-1"))
                .andExpect(jsonPath("$.sources[0].version").value(1))
                .andExpect(jsonPath("$.sources[0].arcs[0].startAngle").value(350000))
                .andExpect(jsonPath("$.sources[1].observationId").value("arc-2"))
                .andExpect(jsonPath("$.sources[1].arcs[0].arcLength").value(10000));

        getAdjudication("adj-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].startAngle").value(355000))
                .andExpect(jsonPath("$.components[0].arcLength").value(10000));
        assertThat(adjudicationCount()).isEqualTo(1);
    }

    @Test
    void adjudicateEmptyIntersectionReturns422AndPersistsNothing() throws Exception {
        // 题干示例续：再与起始 20000 弧长 1000 相交则无交集，422 且不留裁定
        createArc("req-ae1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-ae2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());
        createArc("req-ae3", "arc-3", "op-1", new int[][]{{20000, 1000}}).andExpect(status().isCreated());

        adjudicate("req-ae4", "adj-x", "op-9",
                new String[][]{{"arc-1", "1"}, {"arc-2", "1"}, {"arc-3", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(adjudicationCount()).isZero();
        assertThat(requestLogCount("req-ae4")).isZero();
    }

    @Test
    void adjudicateWithStaleVersionReturns409() throws Exception {
        createArc("req-as1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-as2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());
        mergeArc("req-as3", "arc-1", 1, "op-1", new int[][]{{340000, 30000}}).andExpect(status().isOk());

        adjudicate("req-as4", "adj-2", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void adjudicateWithMissingObservationReturns404() throws Exception {
        createArc("req-a404", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        adjudicate("req-a404b", "adj-3", "op-9", new String[][]{{"arc-1", "1"}, {"arc-x", "1"}})
                .andExpect(status().isNotFound());
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void adjudicateWithTooFewDistinctReferencesReturns400() throws Exception {
        createArc("req-af1", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        adjudicate("req-af2", "adj-4", "op-9", new String[][]{{"arc-1", "1"}, {"arc-1", "1"}})
                .andExpect(status().isBadRequest());
        assertThat(adjudicationCount()).isZero();
    }

    @Test
    void adjudicateRejectsTombstoneAndUnresolvedConflictWith422() throws Exception {
        createArc("req-at1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-at2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());
        createArc("req-at3", "arc-3", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());

        // 墓碑引用：422
        deleteArc("req-at4", "arc-3", 1, "op-1").andExpect(status().isOk());
        adjudicate("req-at5", "adj-5", "op-9", new String[][]{{"arc-1", "1"}, {"arc-3", "2"}})
                .andExpect(status().isUnprocessableEntity());

        // 未解决冲突引用：422；人工裁决清除后裁定放行
        mergeArc("req-at6", "arc-2", 1, "op-1", new int[][]{{355000, 9000}}).andExpect(status().isOk());
        mergeArc("req-at7", "arc-2", 1, "op-2", new int[][]{{350000, 10000}})
                .andExpect(status().isConflict());
        adjudicate("req-at8", "adj-6", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "2"}})
                .andExpect(status().isUnprocessableEntity());
        resolveArc("req-at9", "arc-2", 2, "op-3", new int[][]{{355000, 9000}}).andExpect(status().isOk());
        adjudicate("req-at10", "adj-7", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "2"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components[0].startAngle").value(355000))
                .andExpect(jsonPath("$.components[0].arcLength").value(9000));
        assertThat(adjudicationCount()).isEqualTo(1);
    }

    @Test
    void adjudicationFrozenAfterLaterRevision() throws Exception {
        createArc("req-az1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-az2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());
        adjudicate("req-az3", "adj-8", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isCreated());

        // 后续修订：arc-1 前进到版本 2，旧裁定结果不倒改
        mergeArc("req-az4", "arc-1", 1, "op-1", new int[][]{{10000, 5000}}).andExpect(status().isOk());
        getAdjudication("adj-8")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].version").value(1))
                .andExpect(jsonPath("$.sources[0].arcs[0].startAngle").value(350000))
                .andExpect(jsonPath("$.components[0].startAngle").value(355000))
                .andExpect(jsonPath("$.components[0].arcLength").value(10000));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysFirstResultAndDifferentParamsConflict() throws Exception {
        createArc("req-ai1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-ai2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());

        adjudicate("req-ai3", "adj-9", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isCreated());
        // 同键同参（引用顺序可变）重放：返回首次完整结果，不新增裁定
        adjudicate("req-ai3", "adj-9", "op-9", new String[][]{{"arc-2", "1"}, {"arc-1", "1"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components[0].startAngle").value(355000));
        assertThat(adjudicationCount()).isEqualTo(1);
        assertThat(requestLogCount("req-ai3")).isEqualTo(1);
        // 同键异参：409（操作者变更、裁定标识变更均为改参）
        adjudicate("req-ai3", "adj-9", "op-8", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isConflict());
        adjudicate("req-ai3", "adj-other", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isConflict());
        assertThat(adjudicationCount()).isEqualTo(1);
    }

    @Test
    void adjudicationIdReuseSameParamsReplaysDifferentParams409() throws Exception {
        createArc("req-ak1", "arc-1", "op-1", new int[][]{{350000, 20000}}).andExpect(status().isCreated());
        createArc("req-ak2", "arc-2", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());
        createArc("req-ak3", "arc-3", "op-1", new int[][]{{355000, 10000}}).andExpect(status().isCreated());

        adjudicate("req-ak4", "adj-10", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isCreated());
        // 不同 requestId、同 adjudicationId 同参：重放首次结果
        adjudicate("req-ak5", "adj-10", "op-9", new String[][]{{"arc-2", "1"}, {"arc-1", "1"}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].startAngle").value(355000));
        // 同 adjudicationId 改参：409
        adjudicate("req-ak6", "adj-10", "op-9", new String[][]{{"arc-1", "1"}, {"arc-3", "1"}})
                .andExpect(status().isConflict());
        assertThat(adjudicationCount()).isEqualTo(1);
    }

    @Test
    void failedAdjudicationDoesNotOccupyRequestId() throws Exception {
        createArc("req-an1", "arc-1", "op-1", new int[][]{{0, 100000}}).andExpect(status().isCreated());
        createArc("req-an2", "arc-2", "op-1", new int[][]{{180000, 100000}}).andExpect(status().isCreated());
        // 无交集失败：不占键
        adjudicate("req-an3", "adj-11", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "1"}})
                .andExpect(status().isUnprocessableEntity());
        assertThat(requestLogCount("req-an3")).isZero();
        // 同一 requestId 换上相交参数后正常执行
        mergeArc("req-an4", "arc-2", 1, "op-1", new int[][]{{50000, 100000}}).andExpect(status().isOk());
        adjudicate("req-an3", "adj-12", "op-9", new String[][]{{"arc-1", "1"}, {"arc-2", "2"}})
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components[0].startAngle").value(50000))
                .andExpect(jsonPath("$.components[0].arcLength").value(50000));
    }

    @Test
    void mergeIdempotentReplayReturnsSameVersion() throws Exception {
        createArc("req-am11", "arc-1", "op-1", new int[][]{{10000, 5000}}).andExpect(status().isCreated());
        mergeArc("req-am12", "arc-1", 1, "op-2", new int[][]{{20000, 8000}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mergeArc("req-am12", "arc-1", 1, "op-2", new int[][]{{20000, 8000}})
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(versionCount("arc-1")).isEqualTo(2);
        assertThat(requestLogCount("req-am12")).isEqualTo(1);
    }
}
