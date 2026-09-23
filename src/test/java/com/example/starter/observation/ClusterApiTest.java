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
 * 重复观测簇字段级溯源归并 API 测试：主流程、预览冻结、整体回滚、幂等（换序同参/异参 409/失败不占键）
 * 与归并后只读语义。真实 H2 内存库（MODE=MySQL），不使用 mock 代替数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClusterApiTest {

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
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    /**
     * 创建一条带观测时间的原始记录。observedAt 形如 2026-09-23T10:00:00Z。
     */
    private void createObservation(String requestId, String observationId, String siteKey, String type,
                                   String observedAt, String deviceId,
                                   String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("siteKey", siteKey);
        body.put("observationType", type);
        body.put("observedAt", observedAt);
        body.put("deviceId", deviceId);
        mockMvc.perform(post("/api/observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private ResultActions preview(List<String> recordKeys) throws Exception {
        return mockMvc.perform(post("/api/observations/cluster-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("recordKeys", recordKeys))));
    }

    private Map<String, Object> fieldSource(String sourceRecordKey) {
        return Map.of("sourceRecordKey", sourceRecordKey);
    }

    private ResultActions commit(String requestId, String clusterKey, String canonicalId,
                                 List<Map<String, Object>> members, Map<String, Object> fieldSources,
                                 String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("clusterKey", clusterKey);
        body.put("canonicalRecordId", canonicalId);
        body.put("members", members);
        body.put("fieldSources", fieldSources);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/clusters")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<Map<String, Object>> members(String a, int ga, String b, int gb) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Map.of("recordKey", a, "generation", ga));
        list.add(Map.of("recordKey", b, "generation", gb));
        return list;
    }

    private Map<String, Object> sources(String locationFrom, String readingFrom, String noteFrom) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("location", fieldSource(locationFrom));
        map.put("reading", fieldSource(readingFrom));
        map.put("note", fieldSource(noteFrom));
        return map;
    }

    private void seedThreeRecords() throws Exception {
        createObservation("req-c1", "obs-1", "SITE-A", "TEMP", "2026-09-23T10:00:00Z", "dev-1",
                "站点A", "10.0", "备注一");
        createObservation("req-c2", "obs-2", "SITE-A", "TEMP", "2026-09-23T10:00:20Z", "dev-2",
                "站点B", "10.5", "备注二");
        createObservation("req-c3", "obs-3", "SITE-A", "TEMP", "2026-09-23T10:00:40Z", "dev-3",
                "站点C", "11.0", "备注三");
    }

    // ---------- 预览 ----------

    @Test
    void previewFreezesGenerationsDevicesTimesAndValues() throws Exception {
        seedThreeRecords();
        preview(List.of("obs-1", "obs-2", "obs-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteKey").value("SITE-A"))
                .andExpect(jsonPath("$.observationType").value("TEMP"))
                .andExpect(jsonPath("$.observedAtFrom").value("2026-09-23T10:00:00Z"))
                .andExpect(jsonPath("$.observedAtTo").value("2026-09-23T10:00:40Z"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].recordKey").value("obs-1"))
                .andExpect(jsonPath("$.members[0].generation").value(1))
                .andExpect(jsonPath("$.members[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$.members[0].reading").value("10.0"))
                .andExpect(jsonPath("$.members[2].recordKey").value("obs-3"))
                .andExpect(jsonPath("$.members[2].deviceId").value("dev-3"));
        // 预览只读：不产生任何归并记录，成员仍 ACTIVE
        Integer clusterRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster", Integer.class);
        assertThat(clusterRows).isZero();
    }

    @Test
    void previewIsOrderInsensitiveAndRejectsUnknownRecord() throws Exception {
        seedThreeRecords();
        preview(List.of("obs-3", "obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].recordKey").value("obs-1"));
        preview(List.of("obs-1", "obs-x")).andExpect(status().isNotFound());
    }

    @Test
    void previewRejectsRangeOver60Seconds() throws Exception {
        createObservation("req-r1", "obs-1", "SITE-A", "TEMP", "2026-09-23T10:00:00Z", "dev-1",
                "站点A", "10.0", "备注一");
        createObservation("req-r2", "obs-2", "SITE-A", "TEMP", "2026-09-23T10:01:01Z", "dev-2",
                "站点B", "10.5", "备注二");
        preview(List.of("obs-1", "obs-2")).andExpect(status().isConflict());
    }

    @Test
    void previewRejectsDifferentSiteOrType() throws Exception {
        createObservation("req-s1", "obs-1", "SITE-A", "TEMP", "2026-09-23T10:00:00Z", "dev-1",
                "站点A", "10.0", "备注一");
        createObservation("req-s2", "obs-2", "SITE-B", "TEMP", "2026-09-23T10:00:20Z", "dev-2",
                "站点B", "10.5", "备注二");
        createObservation("req-s3", "obs-3", "SITE-A", "HUM", "2026-09-23T10:00:20Z", "dev-3",
                "站点C", "11.0", "备注三");
        preview(List.of("obs-1", "obs-2")).andExpect(status().isConflict());
        preview(List.of("obs-1", "obs-3")).andExpect(status().isConflict());
    }

    @Test
    void previewRejectsDuplicateKeys() throws Exception {
        seedThreeRecords();
        preview(List.of("obs-1", "obs-1")).andExpect(status().isConflict());
    }

    // ---------- 主流程 ----------

    @Test
    void commitCreatesCanonicalAndImmutableFieldEvidence() throws Exception {
        seedThreeRecords();
        List<Map<String, Object>> memberList = List.of(
                Map.of("recordKey", "obs-1", "generation", 1),
                Map.of("recordKey", "obs-2", "generation", 1),
                Map.of("recordKey", "obs-3", "generation", 1));
        commit("req-m1", "cluster-1", "canon-1", memberList,
                sources("obs-1", "obs-2", "obs-3"), "reviewer-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clusterKey").value("cluster-1"))
                .andExpect(jsonPath("$.canonical.observationId").value("canon-1"))
                .andExpect(jsonPath("$.canonical.version").value(1))
                .andExpect(jsonPath("$.canonical.origin").value("CANONICAL"))
                .andExpect(jsonPath("$.canonical.status").value("ACTIVE"))
                .andExpect(jsonPath("$.canonical.location").value("站点A"))
                .andExpect(jsonPath("$.canonical.reading").value("10.5"))
                .andExpect(jsonPath("$.canonical.note").value("备注三"))
                .andExpect(jsonPath("$.observedAtFrom").value("2026-09-23T10:00:00Z"))
                .andExpect(jsonPath("$.observedAtTo").value("2026-09-23T10:00:40Z"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.fieldSources.location.sourceRecordKey").value("obs-1"))
                .andExpect(jsonPath("$.fieldSources.location.sourceGeneration").value(1))
                .andExpect(jsonPath("$.fieldSources.location.sourceValue").value("站点A"))
                .andExpect(jsonPath("$.fieldSources.reading.sourceRecordKey").value("obs-2"))
                .andExpect(jsonPath("$.fieldSources.reading.sourceValue").value("10.5"))
                .andExpect(jsonPath("$.fieldSources.note.sourceRecordKey").value("obs-3"))
                .andExpect(jsonPath("$.fieldSources.note.sourceValue").value("备注三"));

        // 原记录置 MERGED 并记录归并目标
        mockMvc.perform(get("/api/observations/obs-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedInto").value("canon-1"));
        mockMvc.perform(get("/api/observations/obs-2"))
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.mergedInto").value("canon-1"));

        // 历史版本仍可查
        mockMvc.perform(get("/api/observations/obs-1/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点A"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 数据库证据行数：表头 1、成员 3、字段来源 3
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_member", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_field_source", Integer.class)).isEqualTo(3);
        Integer canonicalVersions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'canon-1'", Integer.class);
        assertThat(canonicalVersions).isEqualTo(1);

        // 按 clusterKey 只读查询
        mockMvc.perform(get("/api/observations/clusters/cluster-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonical.observationId").value("canon-1"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.fieldSources.note.sourceRecordKey").value("obs-3"));
    }

    @Test
    void getUnknownClusterReturns404() throws Exception {
        mockMvc.perform(get("/api/observations/clusters/nope"))
                .andExpect(status().isNotFound());
    }

    // ---------- 失败分支与整体回滚 ----------

    @Test
    void staleGenerationRejectsCommitAndRollsEverythingBack() throws Exception {
        seedThreeRecords();
        // obs-2 在预览后被离线更新，代次前进到 2
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-up1");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "10.5");
        mergeBody.put("note", "更新后的备注");
        mockMvc.perform(post("/api/observations/obs-2/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        List<Map<String, Object>> stale = List.of(
                Map.of("recordKey", "obs-1", "generation", 1),
                Map.of("recordKey", "obs-2", "generation", 1),
                Map.of("recordKey", "obs-3", "generation", 1));
        commit("req-f1", "cluster-f1", "canon-f1", stale,
                sources("obs-1", "obs-2", "obs-3"), "reviewer-1")
                .andExpect(status().isConflict());

        assertNoTraceLeft("cluster-f1", "canon-f1");
        mockMvc.perform(get("/api/observations/obs-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mergedInto").doesNotExist());
    }

    @Test
    void tombstonedAndAlreadyMergedMembersAreRejected() throws Exception {
        seedThreeRecords();
        List<Map<String, Object>> firstMembers = members("obs-1", 1, "obs-2", 1);
        commit("req-g1", "cluster-g1", "canon-g1", firstMembers,
                sources("obs-1", "obs-1", "obs-1"), "reviewer-1")
                .andExpect(status().isCreated());

        // obs-1 已 MERGED：再次入簇 409（含防止把归并产物链环扩展）
        commit("req-g2", "cluster-g2", "canon-g2", members("obs-1", 1, "obs-3", 1),
                sources("obs-1", "obs-1", "obs-3"), "reviewer-1")
                .andExpect(status().isConflict());
        // canonical 主记录也不得作为成员入簇
        commit("req-g3", "cluster-g3", "canon-g3", members("canon-g1", 1, "obs-3", 1),
                sources("canon-g1", "canon-g1", "obs-3"), "reviewer-1")
                .andExpect(status().isConflict());
        assertNoTraceLeft("cluster-g2", "canon-g2");
        assertNoTraceLeft("cluster-g3", "canon-g3");
    }

    @Test
    void deletedMemberIsRejected() throws Exception {
        seedThreeRecords();
        Map<String, Object> deleteBody = Map.of("requestId", "req-d0", "expectedVersion", 1);
        mockMvc.perform(post("/api/observations/obs-2/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        commit("req-h1", "cluster-h1", "canon-h1", members("obs-1", 1, "obs-2", 2),
                sources("obs-1", "obs-1", "obs-1"), "reviewer-1")
                .andExpect(status().isConflict());
        assertNoTraceLeft("cluster-h1", "canon-h1");
    }

    @Test
    void missingOrExtraFieldSourceRejected() throws Exception {
        seedThreeRecords();
        List<Map<String, Object>> memberList = List.of(
                Map.of("recordKey", "obs-1", "generation", 1),
                Map.of("recordKey", "obs-2", "generation", 1));
        // 遗漏 note
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("location", fieldSource("obs-1"));
        missing.put("reading", fieldSource("obs-2"));
        commit("req-e1", "cluster-e1", "canon-e1", memberList, missing, "reviewer-1")
                .andExpect(status().isConflict());
        // 多出非法字段
        Map<String, Object> extra = sources("obs-1", "obs-2", "obs-1");
        extra.put("unknown", fieldSource("obs-1"));
        commit("req-e2", "cluster-e2", "canon-e2", memberList, extra, "reviewer-1")
                .andExpect(status().isConflict());
        // 来源不在簇内
        commit("req-e3", "cluster-e3", "canon-e3", memberList,
                sources("obs-3", "obs-1", "obs-2"), "reviewer-1")
                .andExpect(status().isConflict());
        assertNoTraceLeft("cluster-e1", "canon-e1");
        assertNoTraceLeft("cluster-e2", "canon-e2");
        assertNoTraceLeft("cluster-e3", "canon-e3");
    }

    @Test
    void canonicalKeyConflictsAreRejected() throws Exception {
        seedThreeRecords();
        // 新主记录键与既有记录键冲突
        commit("req-k1", "cluster-k1", "obs-2", members("obs-1", 1, "obs-3", 1),
                sources("obs-1", "obs-1", "obs-3"), "reviewer-1")
                .andExpect(status().isConflict());
        // 新主记录键等于成员键
        commit("req-k2", "cluster-k2", "obs-1", members("obs-1", 1, "obs-3", 1),
                sources("obs-1", "obs-1", "obs-3"), "reviewer-1")
                .andExpect(status().isConflict());
        // 被拒主记录键恰为既有成员：仅断言无归并痕迹，既有成员本身仍存在且 ACTIVE
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key IN ('cluster-k1','cluster-k2')",
                Integer.class)).isZero();
        mockMvc.perform(get("/api/observations/obs-1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mergedInto").doesNotExist());
        mockMvc.perform(get("/api/observations/obs-3"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void clusterKeyMustBeUnique() throws Exception {
        seedThreeRecords();
        commit("req-u1", "cluster-u1", "canon-u1", members("obs-1", 1, "obs-2", 1),
                sources("obs-1", "obs-1", "obs-2"), "reviewer-1")
                .andExpect(status().isCreated());
        // 同 clusterKey 异参：409
        commit("req-u2", "cluster-u1", "canon-u2", members("obs-1", 1, "obs-3", 1),
                sources("obs-1", "obs-1", "obs-3"), "reviewer-1")
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cluster-u1'", Integer.class))
                .isEqualTo(1);
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplaysAndAcceptsReorderedMembersAndFields() throws Exception {
        seedThreeRecords();
        List<Map<String, Object>> memberList = List.of(
                Map.of("recordKey", "obs-3", "generation", 1),
                Map.of("recordKey", "obs-1", "generation", 1),
                Map.of("recordKey", "obs-2", "generation", 1));
        // 字段映射顺序打乱，成员集合换序：与正常顺序提交视为同参
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("note", fieldSource("obs-3"));
        reordered.put("location", fieldSource("obs-1"));
        reordered.put("reading", fieldSource("obs-2"));
        commit("req-i1", "cluster-i1", "canon-i1", memberList, reordered, "reviewer-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canonical.observationId").value("canon-i1"));

        // 再次同参重放：201 原结果且不重复落库
        commit("req-i1", "cluster-i1", "canon-i1",
                        List.of(
                                Map.of("recordKey", "obs-1", "generation", 1),
                                Map.of("recordKey", "obs-2", "generation", 1),
                                Map.of("recordKey", "obs-3", "generation", 1)),
                        sources("obs-1", "obs-2", "obs-3"), "reviewer-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.canonical.reading").value("10.5"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = 'cluster-i1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id = 'canon-i1'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-i1'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() throws Exception {
        seedThreeRecords();
        commit("req-i2", "cluster-i2", "canon-i2", members("obs-1", 1, "obs-2", 1),
                sources("obs-1", "obs-1", "obs-2"), "reviewer-1")
                .andExpect(status().isCreated());
        // 换字段来源：异参 409
        commit("req-i2", "cluster-i3", "canon-i3", members("obs-1", 1, "obs-2", 1),
                sources("obs-2", "obs-1", "obs-2"), "reviewer-1")
                .andExpect(status().isConflict());
    }

    @Test
    void failedCommitDoesNotOccupyKeys() throws Exception {
        seedThreeRecords();
        // 首次失败：时间范围超限（obs-3 与 obs-1 差 40s 合法；构造非法来源先失败）
        commit("req-i3", "cluster-i4", "canon-i4", members("obs-1", 1, "obs-2", 1),
                sources("obs-3", "obs-1", "obs-2"), "reviewer-1")
                .andExpect(status().isConflict());
        // 同 requestId 换上合法参数可成功；clusterKey 与 canonical 键也未被占用
        commit("req-i3", "cluster-i4", "canon-i4", members("obs-1", 1, "obs-2", 1),
                sources("obs-1", "obs-2", "obs-2"), "reviewer-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clusterKey").value("cluster-i4"));
    }

    // ---------- 归并后语义 ----------

    @Test
    void mergedMembersRejectOfflineUpdateAndDelete() throws Exception {
        seedThreeRecords();
        commit("req-m9", "cluster-9", "canon-9", members("obs-1", 1, "obs-2", 1),
                sources("obs-1", "obs-2", "obs-1"), "reviewer-1")
                .andExpect(status().isCreated());

        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-a1");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "被拒绝的地点");
        mergeBody.put("reading", "99.0");
        mergeBody.put("note", "试图更新已归并记录");
        mockMvc.perform(post("/api/observations/obs-1/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/observations/obs-1/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("requestId", "req-a2", "expectedVersion", 1))))
                .andExpect(status().isConflict());
        // 状态未被恢复或改变
        mockMvc.perform(get("/api/observations/obs-1"))
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.location").value("站点A"));
    }

    private void assertNoTraceLeft(String clusterKey, String canonicalId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster WHERE cluster_key = ?",
                Integer.class, clusterKey)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_member WHERE cluster_key = ?",
                Integer.class, clusterKey)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_field_source WHERE cluster_key = ?",
                Integer.class, clusterKey)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current WHERE observation_id = ?",
                Integer.class, canonicalId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?",
                Integer.class, canonicalId)).isZero();
    }
}
