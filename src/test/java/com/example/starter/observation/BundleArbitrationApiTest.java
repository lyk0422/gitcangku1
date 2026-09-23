package com.example.starter.observation;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;
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
 * 关联观测簇与字段级联合裁决 API 测试：建簇冻结、冲突登记、联合裁决四种来源、
 * 墓碑恢复、一致性强制、完整性回滚、幂等重放与证据查询排序。使用真实 H2 内存库与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BundleArbitrationApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-24T08:00:00Z");

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
        jdbcTemplate.update("DELETE FROM bundle_arbitration");
        jdbcTemplate.update("DELETE FROM bundle_conflict");
        jdbcTemplate.update("DELETE FROM bundle_member");
        jdbcTemplate.update("DELETE FROM observation_bundle");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ---------- 请求构造辅助 ----------

    private ResultActions createObservation(String requestId, String observationId, String surveyId,
                                            String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("surveyId", surveyId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions deleteObservation(String requestId, String observationId, int expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createBundle(String requestId, String bundleKey, String surveyId,
                                       List<String> observationIds, List<String> consistentFields,
                                       String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("bundleKey", bundleKey);
        body.put("surveyId", surveyId);
        body.put("observationIds", observationIds);
        body.put("consistentFields", consistentFields);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/bundles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerConflict(String requestId, String bundleKey, String observationId,
                                           int baseVersion, String location, String reading, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/bundles/{bundleKey}/conflicts", bundleKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions arbitrate(String bundleKey, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/bundles/{bundleKey}/arbitrations", bundleKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> arbitrationBody(String requestId, String operator,
                                                Map<String, Integer> expectedVersions,
                                                List<Map<String, Object>> choices,
                                                List<Map<String, Object>> restores) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", operator);
        List<Map<String, Object>> versions = new ArrayList<>();
        expectedVersions.forEach((id, version) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("observationId", id);
            entry.put("expectedVersion", version);
            versions.add(entry);
        });
        body.put("expectedVersions", versions);
        body.put("choices", choices);
        body.put("restores", restores);
        return body;
    }

    private Map<String, Object> choice(long conflictId, String candidateToken, String source, String value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conflictId", conflictId);
        map.put("candidateToken", candidateToken);
        map.put("source", source);
        if (value != null) {
            map.put("value", value);
        }
        return map;
    }

    private Map<String, Object> restore(String observationId, List<Map<String, String>> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("observationId", observationId);
        List<Map<String, String>> fieldList = new ArrayList<>();
        for (Map<String, String> field : fields) {
            Map<String, String> entry = new LinkedHashMap<>(field);
            fieldList.add(entry);
        }
        map.put("fields", fieldList);
        return map;
    }

    private Map<String, String> restoreField(String field, String source, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("source", source);
        if (value != null) {
            map.put("value", value);
        }
        return map;
    }

    private JsonNode getBundleJson(String bundleKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/bundles/{bundleKey}", bundleKey))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode findConflict(JsonNode bundle, String observationId, String field) {
        for (JsonNode conflict : bundle.get("openConflicts")) {
            if (conflict.get("observationId").asText().equals(observationId)
                    && conflict.get("field").asText().equals(field)) {
                return conflict;
            }
        }
        throw new IllegalStateException("conflict not found: " + observationId + "/" + field);
    }

    /**
     * 标准双观测冲突场景：obs-a 地点冲突（base A / current B / candidate C），
     * obs-b 读数冲突（base 1.0 / current 2.0 / candidate 3.0）。建簇后登记两冲突。
     */
    private JsonNode setupTwoObservationBundle(String bundleKey, List<String> consistentFields)
            throws Exception {
        createObservation("req-ca", "obs-a", "S1", "A地", "1.0", "备注a").andExpect(status().isCreated());
        createObservation("req-cb", "obs-b", "S1", "A地", "1.0", "备注b").andExpect(status().isCreated());
        merge("req-ma", "obs-a", 1, "B地", "1.0", "备注a").andExpect(status().isOk());
        merge("req-mb", "obs-b", 1, "A地", "2.0", "备注b").andExpect(status().isOk());
        createBundle("req-bundle", bundleKey, "S1", List.of("obs-a", "obs-b"),
                consistentFields, "reviewer-1").andExpect(status().isCreated());
        registerConflict("req-reg-a", bundleKey, "obs-a", 1, "C地", "1.0", "备注a")
                .andExpect(status().isOk());
        registerConflict("req-reg-b", bundleKey, "obs-b", 1, "A地", "3.0", "备注b")
                .andExpect(status().isOk());
        return getBundleJson(bundleKey);
    }

    // ---------- 建簇 ----------

    @Test
    void createBundleFreezesVersionsAndMarksTombstoneAsPendingRestore() throws Exception {
        createObservation("req-1", "obs-1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-2", "obs-2", null, "A地", "1.0", "备注").andExpect(status().isCreated());
        merge("req-3", "obs-2", 1, "B地", "1.0", "备注").andExpect(status().isOk());
        deleteObservation("req-4", "obs-2", 2).andExpect(status().isOk());

        // obs-2 未声明 surveyId，建簇时归属为簇 surveyId；obs-2 为墓碑以待恢复身份入簇并冻结墓碑版本
        createBundle("req-b1", "bundle-1", "S1", List.of("obs-2", "obs-1"),
                List.of("location", "reading"), "reviewer-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bundleKey").value("bundle-1"))
                .andExpect(jsonPath("$.surveyId").value("S1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.consistentFields[0]").value("location"))
                .andExpect(jsonPath("$.consistentFields[1]").value("reading"))
                .andExpect(jsonPath("$.members.length()").value(2))
                // 成员按 observationId 稳定排序
                .andExpect(jsonPath("$.members[0].observationId").value("obs-1"))
                .andExpect(jsonPath("$.members[0].frozenVersion").value(1))
                .andExpect(jsonPath("$.members[0].role").value("ACTIVE"))
                .andExpect(jsonPath("$.members[0].deleted").value(false))
                .andExpect(jsonPath("$.members[1].observationId").value("obs-2"))
                .andExpect(jsonPath("$.members[1].frozenVersion").value(3))
                .andExpect(jsonPath("$.members[1].role").value("PENDING_RESTORE"))
                .andExpect(jsonPath("$.members[1].deleted").value(true));

        // 观测归属已写入 surveyId
        mockMvc.perform(get("/api/observations/{id}", "obs-2"))
                .andExpect(jsonPath("$.surveyId").value("S1"));
    }

    @Test
    void createBundleRejectsDuplicateKeyCrossBundleAndBadMembership() throws Exception {
        createObservation("req-d1", "obs-d1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-d2", "obs-d2", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-d3", "obs-d3", "S2", "A地", "1.0", "备注").andExpect(status().isCreated());

        createBundle("req-bd1", "bundle-d1", "S1", List.of("obs-d1", "obs-d2"),
                List.of(), "op").andExpect(status().isCreated());

        // bundleKey 唯一：重复键 409
        createBundle("req-bd2", "bundle-d1", "S1", List.of("obs-d1", "obs-d2"),
                List.of(), "op").andExpect(status().isConflict());
        // obs-d1 已在其他未结簇：409
        createBundle("req-bd3", "bundle-d2", "S1", List.of("obs-d1", "obs-d3"),
                List.of(), "op").andExpect(status().isConflict());
        // surveyId 不一致：409
        createBundle("req-bd4", "bundle-d3", "S2", List.of("obs-d2", "obs-d3"),
                List.of(), "op").andExpect(status().isConflict());
        // 成员重复 / 数量越界：400
        createBundle("req-bd5", "bundle-d4", "S1", List.of("obs-d1", "obs-d1"),
                List.of(), "op").andExpect(status().isBadRequest());
        createBundle("req-bd6", "bundle-d5", "S1", List.of("obs-d2"),
                List.of(), "op").andExpect(status().isBadRequest());
        // 非法一致字段：400
        createBundle("req-bd7", "bundle-d6", "S1", List.of("obs-d2", "obs-d3"),
                List.of("species"), "op").andExpect(status().isBadRequest());
        // 业务失败不占 bundleKey / requestId
        Integer bundles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle WHERE bundle_key IN ('bundle-d2','bundle-d3')",
                Integer.class);
        assertThat(bundles).isZero();
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-bd3'", Integer.class);
        assertThat(requestRows).isZero();
    }

    // ---------- 冲突登记与证据 ----------

    @Test
    void registerConflictRecalculatesAndEvidenceIsStableSorted() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-e1", List.of());

        // 证据只读：冲突按观测、字段排序；obs-a 的地点冲突在前
        assertThat(bundle.get("openConflicts").get(0).get("observationId").asText()).isEqualTo("obs-a");
        assertThat(bundle.get("openConflicts").get(0).get("field").asText()).isEqualTo("location");
        assertThat(bundle.get("openConflicts").get(1).get("observationId").asText()).isEqualTo("obs-b");
        assertThat(bundle.get("openConflicts").get(1).get("field").asText()).isEqualTo("reading");
        assertThat(bundle.get("openConflicts").get(0).get("candidateToken").asText()).hasSize(64);

        // 无剩余冲突的候选登记：409，不改动冲突集合
        registerConflict("req-reg-empty", "bundle-e1", "obs-a", 1, "B地", "1.0", "备注a")
                .andExpect(status().isConflict());
        assertThat(getBundleJson("bundle-e1").get("openConflicts").size()).isEqualTo(2);
    }

    @Test
    void reRegisterConflictKeepsIdButRotatesCandidateToken() throws Exception {
        setupTwoObservationBundle("bundle-e2", List.of());
        JsonNode first = findConflict(getBundleJson("bundle-e2"), "obs-a", "location");
        long conflictId = first.get("conflictId").asLong();
        String oldToken = first.get("candidateToken").asText();

        // 服务端把当前地点推进为 D地；离线端改以 v1 为基提交 E地，地点仍冲突，行 id 稳定但 token 变化
        merge("req-e2-fwd", "obs-a", 2, "D地", "1.0", "备注a").andExpect(status().isOk());
        registerConflict("req-e2-rereg", "bundle-e2", "obs-a", 1, "E地", "1.0", "备注a")
                .andExpect(status().isOk());
        JsonNode updated = findConflict(getBundleJson("bundle-e2"), "obs-a", "location");
        assertThat(updated.get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(updated.get("candidateToken").asText()).isNotEqualTo(oldToken);
    }

    @Test
    void tombstoneMemberCannotRegisterCandidate() throws Exception {
        createObservation("req-t1", "obs-t1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-t2", "obs-t2", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        deleteObservation("req-t3", "obs-t2", 1).andExpect(status().isOk());
        createBundle("req-tb", "bundle-t", "S1", List.of("obs-t1", "obs-t2"),
                List.of(), "op").andExpect(status().isCreated());
        registerConflict("req-tr", "bundle-t", "obs-t2", 1, "C地", "1.0", "备注")
                .andExpect(status().isConflict());
    }

    // ---------- 联合裁决主流程 ----------

    @Test
    void arbitrationAppliesFourSourcesAndClosesBundleAtomically() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-a1", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");

        List<Map<String, Object>> choices = List.of(
                // obs-a 地点冲突选 BASE（取基线 A地）
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "BASE", null),
                // obs-b 读数冲突选 VALUE 显式新值 4.25
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "VALUE", "4.25"));
        Map<String, Object> body = arbitrationBody("req-arb-1", "reviewer-1",
                Map.of("obs-a", 2, "obs-b", 2), choices, List.of());
        arbitrate("bundle-a1", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleKey").value("bundle-a1"))
                .andExpect(jsonPath("$.requestId").value("req-arb-1"))
                .andExpect(jsonPath("$.operator").value("reviewer-1"))
                .andExpect(jsonPath("$.arbitrationId").isNotEmpty())
                .andExpect(jsonPath("$.arbitratedAtUtc").value("2026-09-24T08:00:00Z"))
                .andExpect(jsonPath("$.conflictResolutions.length()").value(2))
                .andExpect(jsonPath("$.conflictResolutions[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.conflictResolutions[0].field").value("location"))
                .andExpect(jsonPath("$.conflictResolutions[0].source").value("BASE"))
                .andExpect(jsonPath("$.conflictResolutions[0].value").value("A地"))
                .andExpect(jsonPath("$.conflictResolutions[1].source").value("VALUE"))
                .andExpect(jsonPath("$.conflictResolutions[1].value").value("4.25"))
                // 簇级前后快照
                .andExpect(jsonPath("$.snapshotBefore[0].version").value(2))
                .andExpect(jsonPath("$.snapshotBefore[0].deleted").value(false))
                .andExpect(jsonPath("$.snapshotAfter[0].version").value(3))
                .andExpect(jsonPath("$.snapshotAfter[0].location").value("A地"))
                .andExpect(jsonPath("$.snapshotAfter[1].reading").value("4.25"));

        // 新版本一次性生成，冲突全部关闭，簇关闭
        mockMvc.perform(get("/api/observations/{id}", "obs-a"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("A地"));
        mockMvc.perform(get("/api/observations/{id}", "obs-b"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("4.25"));
        JsonNode after = getBundleJson("bundle-a1");
        assertThat(after.get("status").asText()).isEqualTo("CLOSED");
        assertThat(after.get("openConflicts").size()).isZero();
        assertThat(after.get("arbitrationId").asText()).isNotBlank();
        Integer resolvedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_conflict WHERE bundle_key = 'bundle-a1' AND status = 'RESOLVED'",
                Integer.class);
        assertThat(resolvedRows).isEqualTo(2);
        // 裁决记录可按 id 查询
        String arbitrationId = after.get("arbitrationId").asText();
        mockMvc.perform(get("/api/bundles/arbitrations/{arbitrationId}", arbitrationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotAfter[1].reading").value("4.25"));
        mockMvc.perform(get("/api/bundles/{bundleKey}/arbitrations", "bundle-a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].arbitrationId").value(arbitrationId));
    }

    @Test
    void arbitrationLocalAndRemoteSourcesResolveAsCurrentAndCandidate() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-a2", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");

        List<Map<String, Object>> choices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "REMOTE", null));
        arbitrate("bundle-a2", arbitrationBody("req-arb-2", "op",
                        Map.of("obs-a", 2, "obs-b", 2), choices, List.of()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/observations/{id}", "obs-a"))
                .andExpect(jsonPath("$.location").value("B地"));
        mockMvc.perform(get("/api/observations/{id}", "obs-b"))
                .andExpect(jsonPath("$.reading").value("3.0"));
    }

    @Test
    void closedBundleRejectsAnotherArbitrationAndMembersCanJoinNewBundle() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-a3", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");
        List<Map<String, Object>> choices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "LOCAL", null));
        arbitrate("bundle-a3", arbitrationBody("req-arb-3", "op",
                Map.of("obs-a", 2, "obs-b", 2), choices, List.of())).andExpect(status().isOk());

        // 已结簇不能再次裁决：409
        arbitrate("bundle-a3", arbitrationBody("req-arb-3b", "op",
                Map.of("obs-a", 3, "obs-b", 3), List.of(), List.of())).andExpect(status().isConflict());

        // 关闭后成员可加入新簇（未结簇占用已释放）
        createObservation("req-a3-new", "obs-c", "S1", "A地", "1.0", "备注c").andExpect(status().isCreated());
        createBundle("req-a3-b2", "bundle-a3-second", "S1", List.of("obs-a", "obs-c"),
                List.of(), "op").andExpect(status().isCreated());
    }

    // ---------- 一致性 ----------

    @Test
    void consistentFieldMismatchRejectsAndRollsBackEverything() throws Exception {
        // 声明 location 必须一致：obs-a 候选 C地，obs-b 无地点冲突保持 A地，裁决应整体失败
        JsonNode bundle = setupTwoObservationBundle("bundle-c1", List.of("location"));
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");
        List<Map<String, Object>> choices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "REMOTE", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "LOCAL", null));
        arbitrate("bundle-c1", arbitrationBody("req-arb-c1", "op",
                        Map.of("obs-a", 2, "obs-b", 2), choices, List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("location")));

        // 所有观测、冲突状态与簇状态保持不变
        mockMvc.perform(get("/api/observations/{id}", "obs-a"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("B地"));
        mockMvc.perform(get("/api/observations/{id}", "obs-b"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("2.0"));
        JsonNode after = getBundleJson("bundle-c1");
        assertThat(after.get("status").asText()).isEqualTo("OPEN");
        assertThat(after.get("openConflicts").size()).isEqualTo(2);
        Integer openRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_conflict WHERE status = 'OPEN'", Integer.class);
        assertThat(openRows).isEqualTo(2);
        Integer arbRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration", Integer.class);
        assertThat(arbRows).isZero();
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-arb-c1'", Integer.class);
        assertThat(requestRows).isZero();
    }

    @Test
    void explicitValuesAlignConsistentFieldAndSucceed() throws Exception {
        // 两个观测地点均冲突，统一选择显式值“统一地”，reading 非一致字段各取所需
        createObservation("req-u1", "obs-u1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-u2", "obs-u2", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        merge("req-u3", "obs-u1", 1, "B地", "1.0", "备注").andExpect(status().isOk());
        merge("req-u4", "obs-u2", 1, "B地", "1.0", "备注").andExpect(status().isOk());
        createBundle("req-u5", "bundle-u", "S1", List.of("obs-u1", "obs-u2"),
                List.of("location"), "op").andExpect(status().isCreated());
        registerConflict("req-u6", "bundle-u", "obs-u1", 1, "C地", "1.0", "备注").andExpect(status().isOk());
        registerConflict("req-u7", "bundle-u", "obs-u2", 1, "D地", "1.0", "备注").andExpect(status().isOk());
        JsonNode bundle = getBundleJson("bundle-u");
        JsonNode c1 = findConflict(bundle, "obs-u1", "location");
        JsonNode c2 = findConflict(bundle, "obs-u2", "location");
        List<Map<String, Object>> choices = List.of(
                choice(c1.get("conflictId").asLong(), c1.get("candidateToken").asText(), "VALUE", "统一地"),
                choice(c2.get("conflictId").asLong(), c2.get("candidateToken").asText(), "VALUE", "统一地"));
        arbitrate("bundle-u", arbitrationBody("req-u8", "op",
                        Map.of("obs-u1", 2, "obs-u2", 2), choices, List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotAfter[0].location").value("统一地"))
                .andExpect(jsonPath("$.snapshotAfter[1].location").value("统一地"));
        mockMvc.perform(get("/api/observations/{id}", "obs-u1"))
                .andExpect(jsonPath("$.location").value("统一地"));
        mockMvc.perform(get("/api/observations/{id}", "obs-u2"))
                .andExpect(jsonPath("$.location").value("统一地"));
    }

    // ---------- 墓碑恢复 ----------

    @Test
    void tombstoneRestoreSharesTransactionWithFieldArbitration() throws Exception {
        createObservation("req-r1", "obs-r1", "S1", "A地", "1.0", "备注r1").andExpect(status().isCreated());
        createObservation("req-r2", "obs-r2", "S1", "X地", "5.0", "备注r2").andExpect(status().isCreated());
        merge("req-r3", "obs-r1", 1, "B地", "1.0", "备注r1").andExpect(status().isOk());
        deleteObservation("req-r4", "obs-r2", 1).andExpect(status().isOk());
        createBundle("req-r5", "bundle-r", "S1", List.of("obs-r1", "obs-r2"),
                List.of(), "op").andExpect(status().isCreated());
        registerConflict("req-r6", "bundle-r", "obs-r1", 1, "C地", "1.0", "备注r1")
                .andExpect(status().isOk());
        JsonNode bundle = getBundleJson("bundle-r");
        JsonNode locationConflict = findConflict(bundle, "obs-r1", "location");

        // obs-r2 墓碑恢复：location 取 BASE（末个存活版本 X地），reading 显式 6.0，note 显式新值
        List<Map<String, Object>> restores = List.of(restore("obs-r2", List.of(
                restoreField("note", "VALUE", "恢复备注"),
                restoreField("reading", "VALUE", "6.0"),
                restoreField("location", "BASE", null))));
        List<Map<String, Object>> choices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "REMOTE", null));
        arbitrate("bundle-r", arbitrationBody("req-r7", "op",
                        Map.of("obs-r1", 2, "obs-r2", 2), choices, restores))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredObservations.length()").value(1))
                .andExpect(jsonPath("$.restoredObservations[0].observationId").value("obs-r2"))
                .andExpect(jsonPath("$.restoredObservations[0].fields.length()").value(3))
                .andExpect(jsonPath("$.snapshotAfter[1].deleted").value(false))
                .andExpect(jsonPath("$.snapshotAfter[1].version").value(3))
                .andExpect(jsonPath("$.snapshotAfter[1].location").value("X地"))
                .andExpect(jsonPath("$.snapshotAfter[1].reading").value("6.0"))
                .andExpect(jsonPath("$.snapshotAfter[1].note").value("恢复备注"));

        mockMvc.perform(get("/api/observations/{id}", "obs-r2"))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("X地"))
                .andExpect(jsonPath("$.reading").value("6.0"));
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-r2'", Integer.class);
        assertThat(versionRows).isEqualTo(3);
    }

    @Test
    void restoreWithoutRequiredFieldsRollsBackAndKeepsTombstone() throws Exception {
        createObservation("req-x1", "obs-x1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-x2", "obs-x2", "S1", "X地", "5.0", "备注2").andExpect(status().isCreated());
        deleteObservation("req-x3", "obs-x2", 1).andExpect(status().isOk());
        createBundle("req-x4", "bundle-x", "S1", List.of("obs-x1", "obs-x2"),
                List.of(), "op").andExpect(status().isCreated());

        // 缺少 reading 必填来源：400，墓碑保持，无新版本，requestId 不占键
        List<Map<String, Object>> restores = List.of(restore("obs-x2", List.of(
                restoreField("location", "BASE", null),
                restoreField("note", "VALUE", "恢复备注"))));
        arbitrate("bundle-x", arbitrationBody("req-x5", "op",
                        Map.of("obs-x1", 1, "obs-x2", 2), List.of(), restores))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/observations/{id}", "obs-x2"))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2));
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-x5'", Integer.class);
        assertThat(requestRows).isZero();
        assertThat(getBundleJson("bundle-x").get("status").asText()).isEqualTo("OPEN");

        // 非法来源 LOCAL 不允许用于恢复：400
        List<Map<String, Object>> badSource = List.of(restore("obs-x2", List.of(
                restoreField("location", "LOCAL", null),
                restoreField("reading", "VALUE", "6.0"),
                restoreField("note", "VALUE", "恢复备注"))));
        arbitrate("bundle-x", arbitrationBody("req-x6", "op",
                        Map.of("obs-x1", 1, "obs-x2", 2), List.of(), badSource))
                .andExpect(status().isBadRequest());
    }

    @Test
    void arbitrationWithoutConflictsCanRestoreTombstoneOnly() throws Exception {
        createObservation("req-o1", "obs-o1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-o2", "obs-o2", "S1", "X地", "5.0", "备注2").andExpect(status().isCreated());
        deleteObservation("req-o3", "obs-o2", 1).andExpect(status().isOk());
        createBundle("req-o4", "bundle-o", "S1", List.of("obs-o1", "obs-o2"),
                List.of(), "op").andExpect(status().isCreated());

        // 簇内无未解决冲突：choices 为空，仅恢复墓碑
        List<Map<String, Object>> restores = List.of(restore("obs-o2", List.of(
                restoreField("location", "BASE", null),
                restoreField("reading", "BASE", null),
                restoreField("note", "BASE", null))));
        arbitrate("bundle-o", arbitrationBody("req-o5", "op",
                        Map.of("obs-o1", 1, "obs-o2", 2), List.of(), restores))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflictResolutions.length()").value(0))
                .andExpect(jsonPath("$.snapshotAfter[1].deleted").value(false))
                .andExpect(jsonPath("$.snapshotAfter[1].reading").value("5.0"));
    }

    // ---------- 完整性校验与回滚 ----------

    @Test
    void incompleteChoicesAndVersionsAreRejectedWithoutSideEffects() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-f1", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");

        // 遗漏一个冲突：400
        arbitrate("bundle-f1", arbitrationBody("req-f1", "op",
                        Map.of("obs-a", 2, "obs-b", 2),
                        List.of(choice(locationConflict.get("conflictId").asLong(),
                                locationConflict.get("candidateToken").asText(), "LOCAL", null)),
                        List.of()))
                .andExpect(status().isBadRequest());
        // 多余冲突 id：400
        arbitrate("bundle-f1", arbitrationBody("req-f2", "op",
                        Map.of("obs-a", 2, "obs-b", 2),
                        List.of(
                                choice(locationConflict.get("conflictId").asLong(),
                                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                                choice(readingConflict.get("conflictId").asLong(),
                                        readingConflict.get("candidateToken").asText(), "LOCAL", null),
                                choice(999999L, "whatever", "LOCAL", null)),
                        List.of()))
                .andExpect(status().isBadRequest());
        // expectedVersion 遗漏成员：400
        arbitrate("bundle-f1", arbitrationBody("req-f3", "op",
                        Map.of("obs-a", 2),
                        List.of(
                                choice(locationConflict.get("conflictId").asLong(),
                                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                                choice(readingConflict.get("conflictId").asLong(),
                                        readingConflict.get("candidateToken").asText(), "LOCAL", null)),
                        List.of()))
                .andExpect(status().isBadRequest());
        // expectedVersion 不匹配：409 并携带当前版本
        arbitrate("bundle-f1", arbitrationBody("req-f4", "op",
                        Map.of("obs-a", 9, "obs-b", 2),
                        List.of(
                                choice(locationConflict.get("conflictId").asLong(),
                                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                                choice(readingConflict.get("conflictId").asLong(),
                                        readingConflict.get("candidateToken").asText(), "LOCAL", null)),
                        List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        // VALUE 来源缺少显式值：400
        arbitrate("bundle-f1", arbitrationBody("req-f5", "op",
                        Map.of("obs-a", 2, "obs-b", 2),
                        List.of(
                                choice(locationConflict.get("conflictId").asLong(),
                                        locationConflict.get("candidateToken").asText(), "VALUE", null),
                                choice(readingConflict.get("conflictId").asLong(),
                                        readingConflict.get("candidateToken").asText(), "LOCAL", null)),
                        List.of()))
                .andExpect(status().isBadRequest());

        // 全部失败后无任何状态变化，requestId 均不占键
        for (String requestId : List.of("req-f1", "req-f2", "req-f3", "req-f4", "req-f5")) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
            assertThat(rows).as(requestId).isZero();
        }
        assertThat(getBundleJson("bundle-f1").get("status").asText()).isEqualTo("OPEN");
        mockMvc.perform(get("/api/observations/{id}", "obs-a"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void staleCandidateTokenIsRejectedAsConflict() throws Exception {
        setupTwoObservationBundle("bundle-f2", List.of());
        JsonNode before = findConflict(getBundleJson("bundle-f2"), "obs-a", "location");
        JsonNode readingConflict = findConflict(getBundleJson("bundle-f2"), "obs-b", "reading");

        // 重新登记 obs-a 候选（token 轮换），旧请求携带旧 token：409
        merge("req-f2-fwd", "obs-a", 2, "D地", "1.0", "备注a").andExpect(status().isOk());
        registerConflict("req-f2-rereg", "bundle-f2", "obs-a", 1, "E地", "1.0", "备注a")
                .andExpect(status().isOk());

        List<Map<String, Object>> choices = List.of(
                choice(before.get("conflictId").asLong(), before.get("candidateToken").asText(),
                        "REMOTE", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "LOCAL", null));
        arbitrate("bundle-f2", arbitrationBody("req-f2-arb", "op",
                        Map.of("obs-a", 3, "obs-b", 2), choices, List.of()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("candidate")));
        assertThat(getBundleJson("bundle-f2").get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void deletingMemberCascadesOpenConflictsAndArbitrationMustReconcile() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-f3", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");

        // 删除 obs-a：其 OPEN 冲突级联清除，obs-a 成为墓碑
        deleteObservation("req-f3-del", "obs-a", 2).andExpect(status().isOk());

        // 仍按旧冲突集合提交（含已消失的 obs-a 冲突）：400
        List<Map<String, Object>> staleChoices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "LOCAL", null));
        arbitrate("bundle-f3", arbitrationBody("req-f3-bad", "op",
                        Map.of("obs-a", 3, "obs-b", 2), staleChoices, List.of()))
                .andExpect(status().isBadRequest());

        // 正确请求：只解决 obs-b 读数冲突，obs-a 墓碑保持不恢复；最终 obs-a 仍墓碑，obs-b 推进版本
        List<Map<String, Object>> validChoices = List.of(
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "REMOTE", null));
        arbitrate("bundle-f3", arbitrationBody("req-f3-ok", "op",
                        Map.of("obs-a", 3, "obs-b", 2), validChoices, List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotAfter[0].deleted").value(true))
                .andExpect(jsonPath("$.snapshotAfter[0].version").value(3))
                .andExpect(jsonPath("$.snapshotAfter[1].reading").value("3.0"));
        mockMvc.perform(get("/api/observations/{id}", "obs-a"))
                .andExpect(jsonPath("$.deleted").value(true));
    }

    // ---------- 幂等 ----------

    @Test
    void sameParamsReplayFirstSnapshotIgnoringOrdering() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-i1", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");
        List<Map<String, Object>> choices = List.of(
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "REMOTE", null),
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "REMOTE", null));
        // expectedVersions 与 choices 换序
        Map<String, Object> body = arbitrationBody("req-arb-i1", "op",
                new LinkedHashMap<>(Map.of("obs-b", 2, "obs-a", 2)), choices, List.of());

        arbitrate("bundle-i1", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotAfter[0].location").value("C地"));
        arbitrate("bundle-i1", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotAfter[0].location").value("C地"));

        // 只生成一次新版本（两观测各推进到 v3）与一张裁决记录，requestId 只占一键
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id IN ('obs-a','obs-b')",
                Integer.class);
        assertThat(versionRows).isEqualTo(6);
        Integer arbRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = 'bundle-i1'", Integer.class);
        assertThat(arbRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-arb-i1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void sameRequestIdWithDifferentParamsReturns409() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-i2", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        JsonNode readingConflict = findConflict(bundle, "obs-b", "reading");
        List<Map<String, Object>> localChoices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "LOCAL", null));
        arbitrate("bundle-i2", arbitrationBody("req-arb-i2", "op",
                        Map.of("obs-a", 2, "obs-b", 2), localChoices, List.of()))
                .andExpect(status().isOk());

        // 同 requestId 改选择：409
        List<Map<String, Object>> remoteChoices = List.of(
                choice(locationConflict.get("conflictId").asLong(),
                        locationConflict.get("candidateToken").asText(), "REMOTE", null),
                choice(readingConflict.get("conflictId").asLong(),
                        readingConflict.get("candidateToken").asText(), "LOCAL", null));
        arbitrate("bundle-i2", arbitrationBody("req-arb-i2", "op",
                        Map.of("obs-a", 3, "obs-b", 3), remoteChoices, List.of()))
                .andExpect(status().isConflict());
    }

    @Test
    void failedArbitrationDoesNotOccupyRequestId() throws Exception {
        JsonNode bundle = setupTwoObservationBundle("bundle-i3", List.of());
        JsonNode locationConflict = findConflict(bundle, "obs-a", "location");
        // 缺少 obs-b 冲突选择，失败
        arbitrate("bundle-i3", arbitrationBody("req-arb-i3", "op",
                        Map.of("obs-a", 2, "obs-b", 2),
                        List.of(choice(locationConflict.get("conflictId").asLong(),
                                locationConflict.get("candidateToken").asText(), "LOCAL", null)),
                        List.of()))
                .andExpect(status().isBadRequest());
        // 同一 requestId 换完整合法参数后成功
        JsonNode refreshed = getBundleJson("bundle-i3");
        JsonNode readingConflict = findConflict(refreshed, "obs-b", "reading");
        arbitrate("bundle-i3", arbitrationBody("req-arb-i3", "op",
                        Map.of("obs-a", 2, "obs-b", 2),
                        List.of(
                                choice(locationConflict.get("conflictId").asLong(),
                                        locationConflict.get("candidateToken").asText(), "LOCAL", null),
                                choice(readingConflict.get("conflictId").asLong(),
                                        readingConflict.get("candidateToken").asText(), "LOCAL", null)),
                        List.of()))
                .andExpect(status().isOk());
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-arb-i3'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void createBundleSameRequestIdSameParamsReplays() throws Exception {
        createObservation("req-p1", "obs-p1", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        createObservation("req-p2", "obs-p2", "S1", "A地", "1.0", "备注").andExpect(status().isCreated());
        List<String> reversed = List.of("obs-p2", "obs-p1");
        List<String> ordered = List.of("obs-p1", "obs-p2");
        createBundle("req-pb", "bundle-p", "S1", reversed, List.of("reading"), "op")
                .andExpect(status().isCreated());
        // 观测换序视为同参：重放首次快照
        createBundle("req-pb", "bundle-p", "S1", ordered, List.of("reading"), "op")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[0].observationId").value("obs-p1"));
        Integer bundleRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle WHERE bundle_key = 'bundle-p'", Integer.class);
        assertThat(bundleRows).isEqualTo(1);
    }

    // ---------- 查询边界 ----------

    @Test
    void missingBundleAndArbitrationReturn404() throws Exception {
        mockMvc.perform(get("/api/bundles/{bundleKey}", "bundle-missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bundles/arbitrations/{arbitrationId}", "arb-missing"))
                .andExpect(status().isNotFound());
    }
}
