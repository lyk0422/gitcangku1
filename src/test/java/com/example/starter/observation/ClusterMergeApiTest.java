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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重复观测簇字段级溯源归并 API 测试：候选预览、主流程、整体回滚、字段溯源、幂等（换序同参/异参 409）、
 * MERGED 拒绝后续更新、clusterKey/新主键冲突与归并链环。使用真实 H2 内存库（MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClusterMergeApiTest {

    private static final String SITE = "S1";
    private static final String TYPE = "T1";
    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-23T10:00:30Z");
    private static final Instant T2 = Instant.parse("2026-09-23T10:00:55Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM cluster_field_source");
        jdbcTemplate.update("DELETE FROM cluster_member");
        jdbcTemplate.update("DELETE FROM duplicate_cluster");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createDedup(String requestId, String id, String site, String type,
                                      Instant observedAt, String device, String location,
                                      String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", id);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("siteKey", site);
        body.put("type", type);
        body.put("observedAt", observedAt.toString());
        body.put("deviceId", device);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createMember(String suffix) throws Exception {
        return createDedup("req-create-" + suffix, "obs-" + suffix, SITE, TYPE,
                switch (suffix) {
                    case "a" -> T0;
                    case "b" -> T1;
                    default -> T2;
                },
                "dev-" + suffix, "L-" + suffix,
                switch (suffix) {
                    case "a" -> "1.100";
                    case "b" -> "2.200";
                    default -> "3.300";
                },
                "N-" + suffix);
    }

    private Map<String, Object> memberRef(String key, int generation) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("recordKey", key);
        ref.put("generation", generation);
        return ref;
    }

    private ResultActions mergeCluster(String requestId, String clusterKey, String canonicalKey,
                                       List<Map<String, Object>> members,
                                       Map<String, String> fieldSources) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("clusterKey", clusterKey);
        body.put("canonicalRecordKey", canonicalKey);
        body.put("members", members);
        body.put("fieldSources", fieldSources);
        return mockMvc.perform(post("/api/observation-clusters/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions preview(Instant anchor, int windowSeconds) throws Exception {
        return mockMvc.perform(get("/api/observation-clusters/candidates")
                .param("siteKey", SITE)
                .param("type", TYPE)
                .param("observedAt", anchor.toString())
                .param("windowSeconds", String.valueOf(windowSeconds)));
    }

    private Map<String, String> sources(String location, String reading, String note) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("location", location);
        map.put("reading", reading);
        map.put("note", note);
        return map;
    }

    private int countSql(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private String mergeStatus(String id) {
        return jdbcTemplate.queryForObject(
                "SELECT merge_status FROM observation_current WHERE observation_id = ?",
                String.class, id);
    }

    private List<Map<String, Object>> threeMembers() {
        return List.of(memberRef("obs-a", 1), memberRef("obs-b", 1), memberRef("obs-c", 1));
    }

    // ---------- 预览 ----------

    @Test
    void previewReturnsOnlyActiveUngroupedWithinWindow() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");

        // 锚点取中间记录，窗口 25 秒：范围 [:05, :55]，b(30) 与 c(55，含边界) 在内；a(0) 超出
        preview(T1, 25)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowSeconds").value(25))
                .andExpect(jsonPath("$.candidates.length()").value(2))
                .andExpect(jsonPath("$.candidates[0].recordKey").value("obs-b"))
                .andExpect(jsonPath("$.candidates[0].generation").value(1))
                .andExpect(jsonPath("$.candidates[0].deviceId").value("dev-b"))
                .andExpect(jsonPath("$.candidates[1].recordKey").value("obs-c"));

        // 默认/上限 60 秒：三条全部在窗内
        mockMvc.perform(get("/api/observation-clusters/candidates")
                        .param("siteKey", SITE)
                        .param("type", TYPE)
                        .param("observedAt", T1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(3));
    }

    @Test
    void previewExcludesOtherSiteTypeTombstoneAndMerged() throws Exception {
        createMember("a");
        createMember("b");
        createDedup("req-other-site", "obs-os", "OTHER", TYPE, T0, "dev-os", "L", "1.0", "N")
                .andExpect(status().isCreated());
        createDedup("req-far", "obs-far", SITE, TYPE,
                Instant.parse("2026-09-23T10:05:00Z"), "dev-far", "L", "1.0", "N")
                .andExpect(status().isCreated());

        preview(T1, 60)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(2));

        // 归并 a,b 后，两者不再出现在候选中，canonical（observedAt=窗口上限）作为新 ACTIVE 记录可被预览
        mergeCluster("req-m1", "cluster-1", "canon-1",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-b", "obs-a", "obs-a"))
                .andExpect(status().isCreated());

        preview(T1, 60)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].recordKey").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("obs-a"))))
                .andExpect(jsonPath("$.candidates[*].recordKey").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("obs-b"))));
    }

    @Test
    void previewRejectsWindowOutOfRange() throws Exception {
        preview(T1, 61).andExpect(status().isBadRequest());
    }

    // ---------- 主流程与字段溯源 ----------

    @Test
    void mergeCreatesCanonicalEvidenceAndMarksMembersMerged() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");

        mergeCluster("req-merge-main", "cluster-main", "canon-main",
                threeMembers(), sources("obs-b", "obs-c", "obs-a"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clusterKey").value("cluster-main"))
                .andExpect(jsonPath("$.canonical.observationId").value("canon-main"))
                .andExpect(jsonPath("$.canonical.version").value(1))
                .andExpect(jsonPath("$.canonical.mergeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.canonical.siteKey").value(SITE))
                .andExpect(jsonPath("$.canonical.type").value(TYPE))
                .andExpect(jsonPath("$.canonical.observedAt").value(T2.toString()))
                .andExpect(jsonPath("$.canonical.location").value("L-b"))
                .andExpect(jsonPath("$.canonical.reading").value("3.300"))
                .andExpect(jsonPath("$.canonical.note").value("N-a"))
                .andExpect(jsonPath("$.windowStart").value(T0.toString()))
                .andExpect(jsonPath("$.windowEnd").value(T2.toString()))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].mergeStatus").value("MERGED"))
                .andExpect(jsonPath("$.members[1].generation").value(1))
                .andExpect(jsonPath("$.fieldSources.length()").value(3));

        // 成员当前状态冻结为 MERGED，generation 不变
        assertThat(mergeStatus("obs-a")).isEqualTo("MERGED");
        assertThat(mergeStatus("obs-b")).isEqualTo("MERGED");
        assertThat(mergeStatus("obs-c")).isEqualTo("MERGED");
        assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE merge_status = 'MERGED'")).isEqualTo(3);
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster")).isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM cluster_member")).isEqualTo(3);
        assertThat(countSql("SELECT COUNT(*) FROM cluster_field_source")).isEqualTo(3);
        // canonical 自身历史只有 generation 1
        assertThat(countSql("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'canon-main'"))
                .isEqualTo(1);

        // 只读查询返回主记录、成员与字段来源
        mockMvc.perform(get("/api/observation-clusters/cluster-main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonical.observationId").value("canon-main"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.fieldSources[?(@.field=='reading')].sourceRecordKey").value(
                        org.hamcrest.Matchers.contains("obs-c")));
    }

    @Test
    void getMissingClusterReturns404() throws Exception {
        mockMvc.perform(get("/api/observation-clusters/nope")).andExpect(status().isNotFound());
    }

    // ---------- 失败分支与整体回滚 ----------

    @Test
    void staleGenerationReturns409AndRollsBackEverything() throws Exception {
        createMember("a");
        createMember("b");

        // 成员 a 在预览后被离线更新，generation 前进到 2
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-bump");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "L-a2");
        mergeBody.put("reading", "1.100");
        mergeBody.put("note", "N-a");
        mockMvc.perform(post("/api/observations/obs-a/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 仍提交旧 generation=1 → 409
        mergeCluster("req-stale", "cluster-stale", "canon-stale",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-b", "obs-a", "obs-a"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));

        assertNoClusterSideEffects("req-stale", "cluster-stale", "canon-stale");
        assertThat(mergeStatus("obs-a")).isEqualTo("ACTIVE");
        assertThat(mergeStatus("obs-b")).isEqualTo("ACTIVE");
    }

    @Test
    void tombstonedMemberReturns409AndRollsBack() throws Exception {
        createMember("a");
        createMember("b");
        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-del-b");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/obs-b/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());

        mergeCluster("req-tomb", "cluster-tomb", "canon-tomb",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 2)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isConflict());

        assertNoClusterSideEffects("req-tomb", "cluster-tomb", "canon-tomb");
        assertThat(mergeStatus("obs-a")).isEqualTo("ACTIVE");
    }

    @Test
    void alreadyMergedMemberReturns409AndRollsBack() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");
        mergeCluster("req-first", "cluster-first", "canon-first",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-b", "obs-a", "obs-a"))
                .andExpect(status().isCreated());

        // obs-a 已 MERGED，再次作为成员提交 → 409，整体回滚，obs-c 不被置 MERGED
        mergeCluster("req-second", "cluster-second", "canon-second",
                List.of(memberRef("obs-a", 1), memberRef("obs-c", 1)),
                sources("obs-c", "obs-c", "obs-c"))
                .andExpect(status().isConflict());

        assertThat(mergeStatus("obs-a")).isEqualTo("MERGED");
        assertThat(mergeStatus("obs-c")).isEqualTo("ACTIVE");
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cluster-second'")).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-second'")).isZero();
    }

    @Test
    void missingOrExtraFieldSourceReturns409() throws Exception {
        createMember("a");
        createMember("b");

        Map<String, String> missing = new LinkedHashMap<>();
        missing.put("location", "obs-a");
        missing.put("reading", "obs-a");
        mergeCluster("req-miss", "cluster-miss", "canon-miss",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)), missing)
                .andExpect(status().isConflict());
        assertNoClusterSideEffects("req-miss", "cluster-miss", "canon-miss");

        Map<String, String> extra = sources("obs-a", "obs-a", "obs-a");
        extra.put("unknownField", "obs-b");
        mergeCluster("req-extra", "cluster-extra", "canon-extra",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)), extra)
                .andExpect(status().isConflict());
        assertNoClusterSideEffects("req-extra", "cluster-extra", "canon-extra");
    }

    @Test
    void fieldSourceOutsideClusterReturns409() throws Exception {
        createMember("a");
        createMember("b");
        createDedup("req-out", "obs-out", SITE, TYPE, T0, "dev-out", "L-out", "9.900", "N-out");

        mergeCluster("req-outsrc", "cluster-outsrc", "canon-outsrc",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-out", "obs-a", "obs-a"))
                .andExpect(status().isConflict());
        assertNoClusterSideEffects("req-outsrc", "cluster-outsrc", "canon-outsrc");
    }

    @Test
    void spreadExceedingSixtySecondsReturns409() throws Exception {
        createMember("a");
        createMember("b");
        createDedup("req-create-wide", "obs-wide", SITE, TYPE,
                Instant.parse("2026-09-23T10:01:01Z"), "dev-wide", "L-w", "5.0", "N-w");

        mergeCluster("req-wide", "cluster-wide", "canon-wide",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1), memberRef("obs-wide", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isConflict());
        assertNoClusterSideEffects("req-wide", "cluster-wide", "canon-wide");
    }

    @Test
    void mismatchedSiteOrTypeReturns409() throws Exception {
        createMember("a");
        createDedup("req-other", "obs-mismatch", "OTHER", TYPE, T0, "dev-x", "L", "1.0", "N");

        mergeCluster("req-mismatch", "cluster-mismatch", "canon-mismatch",
                List.of(memberRef("obs-a", 1), memberRef("obs-mismatch", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isConflict());
        assertNoClusterSideEffects("req-mismatch", "cluster-mismatch", "canon-mismatch");
    }

    @Test
    void canonicalKeyConflictReturns409() throws Exception {
        createMember("a");
        createMember("b");

        mergeCluster("req-keyconflict", "cluster-keyconflict", "obs-a",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isConflict());
        assertNoClusterSideEffects("req-keyconflict", "cluster-keyconflict", null);
    }

    @Test
    void clusterKeyReuseReturns409() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");
        createDedup("req-d", "obs-d", SITE, TYPE, T1, "dev-d", "L-d", "4.400", "N-d");
        mergeCluster("req-reuse-1", "cluster-reuse", "canon-reuse-1",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isCreated());

        // 合法的两成员集合，但复用已存在的 clusterKey → 409（clusterKey 全局唯一）
        mergeCluster("req-reuse-2", "cluster-reuse", "canon-reuse-2",
                List.of(memberRef("obs-c", 1), memberRef("obs-d", 1)),
                sources("obs-c", "obs-c", "obs-c"))
                .andExpect(status().isConflict());
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cluster-reuse'"))
                .isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-reuse-2'"))
                .isZero();
        assertThat(mergeStatus("obs-c")).isEqualTo("ACTIVE");
        assertThat(mergeStatus("obs-d")).isEqualTo("ACTIVE");
    }

    @Test
    void mergeChainingIsForbidden() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");
        // 第一簇产生 canonical canon-chain（它是 ACTIVE）
        mergeCluster("req-chain-1", "cluster-chain-1", "canon-chain",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isCreated());

        // 试图把 canonical 再当成员与 obs-c 组成新簇 → 409，禁止形成归并链环
        mergeCluster("req-chain-2", "cluster-chain-2", "canon-chain-2",
                List.of(memberRef("canon-chain", 1), memberRef("obs-c", 1)),
                sources("canon-chain", "canon-chain", "canon-chain"))
                .andExpect(status().isConflict());
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cluster-chain-2'"))
                .isZero();
        assertThat(mergeStatus("canon-chain")).isEqualTo("ACTIVE");
        assertThat(mergeStatus("obs-c")).isEqualTo("ACTIVE");
    }

    // ---------- MERGED 记录拒绝后续离线更新/恢复 ----------

    @Test
    void mergedMembersRejectOfflineMergeDeleteAndResolve() throws Exception {
        createMember("a");
        createMember("b");
        mergeCluster("req-lock", "cluster-lock", "canon-lock",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isCreated());

        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-post-merge");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "L-a");
        mergeBody.put("reading", "1.100");
        mergeBody.put("note", "changed");
        mockMvc.perform(post("/api/observations/obs-a/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isConflict());

        Map<String, Object> deleteBody = new LinkedHashMap<>();
        deleteBody.put("requestId", "req-post-delete");
        deleteBody.put("expectedVersion", 1);
        mockMvc.perform(post("/api/observations/obs-a/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isConflict());

        // MERGED 记录同样拒绝显式冲突解决（在版本/冲突重算之前即拒绝）
        Map<String, Object> resolveBody = new LinkedHashMap<>();
        resolveBody.put("requestId", "req-post-resolve");
        resolveBody.put("resolutionId", "res-post-merge");
        resolveBody.put("baseVersion", 1);
        resolveBody.put("expectedCurrentVersion", 1);
        resolveBody.put("location", "L-a");
        resolveBody.put("reading", "1.100");
        resolveBody.put("note", "changed");
        resolveBody.put("selections", Map.of());
        resolveBody.put("operator", "reviewer");
        mockMvc.perform(post("/api/observations/obs-a/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveBody)))
                .andExpect(status().isConflict());
        assertThat(countSql("SELECT COUNT(*) FROM conflict_resolution WHERE resolution_id = 'res-post-merge'"))
                .isZero();

        // 历史仍可查
        mockMvc.perform(get("/api/observations/obs-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergeStatus").value("MERGED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.location").value("L-a"));
        mockMvc.perform(get("/api/observations/obs-a/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("L-a"));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReorderedMembersAndFieldsReplays() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");

        Map<String, String> fieldSources = new LinkedHashMap<>();
        fieldSources.put("note", "obs-a");
        fieldSources.put("reading", "obs-c");
        fieldSources.put("location", "obs-b");
        List<Map<String, Object>> reordered = new java.util.ArrayList<>(threeMembers());
        java.util.Collections.reverse(reordered);

        mergeCluster("req-idem", "cluster-idem", "canon-idem", reordered, fieldSources)
                .andExpect(status().isCreated());
        // 同参（成员换序、字段映射按键比较）重放：返回原结果，不重复创建
        mergeCluster("req-idem", "cluster-idem", "canon-idem", threeMembers(),
                sources("obs-b", "obs-c", "obs-a"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canonical.observationId").value("canon-idem"));

        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster WHERE request_id = 'req-idem'")).isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-idem'"))
                .isEqualTo(1);
        assertThat(countSql("SELECT COUNT(*) FROM request_log WHERE request_id = 'req-idem'")).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        createMember("a");
        createMember("b");
        createMember("c");

        mergeCluster("req-idem-diff", "cluster-idem-diff-1", "canon-idem-diff",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isCreated());

        // 同 requestId 更换 clusterKey 与成员 → 异参 409
        mergeCluster("req-idem-diff", "cluster-idem-diff-2", "canon-other",
                List.of(memberRef("obs-a", 1), memberRef("obs-c", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isConflict());
    }

    @Test
    void failedMergeDoesNotOccupyRequestId() throws Exception {
        createMember("a");
        createMember("b");

        // 首次缺字段来源失败 409
        Map<String, String> incomplete = new LinkedHashMap<>();
        incomplete.put("location", "obs-a");
        incomplete.put("reading", "obs-a");
        mergeCluster("req-recover", "cluster-recover", "canon-recover",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)), incomplete)
                .andExpect(status().isConflict());
        assertThat(countSql("SELECT COUNT(*) FROM request_log WHERE request_id = 'req-recover'")).isZero();

        // 同一 requestId 补齐参数后成功
        mergeCluster("req-recover", "cluster-recover", "canon-recover",
                List.of(memberRef("obs-a", 1), memberRef("obs-b", 1)),
                sources("obs-a", "obs-a", "obs-a"))
                .andExpect(status().isCreated());
        assertThat(countSql("SELECT COUNT(*) FROM request_log WHERE request_id = 'req-recover'")).isEqualTo(1);
    }

    private void assertNoClusterSideEffects(String requestId, String clusterKey, String canonicalKey) {
        assertThat(countSql("SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = ?", clusterKey)).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM cluster_member WHERE cluster_key = ?", clusterKey)).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM cluster_field_source WHERE cluster_key = ?", clusterKey)).isZero();
        assertThat(countSql("SELECT COUNT(*) FROM request_log WHERE request_id = ?", requestId)).isZero();
        if (canonicalKey != null) {
            assertThat(countSql("SELECT COUNT(*) FROM observation_current WHERE observation_id = ?",
                    canonicalKey)).isZero();
        }
    }
}
