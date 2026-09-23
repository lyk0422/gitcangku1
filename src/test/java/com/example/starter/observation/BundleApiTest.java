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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 关联观测簇与字段级联合裁决 API 测试（真实 H2 内存库，MySQL 兼容模式）：
 * 覆盖联合裁决主流程、墓碑恢复、完整性回滚、幂等（换序同参/异参 409/失败不占键）与证据只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BundleApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM field_conflict");
        jdbcTemplate.update("DELETE FROM bundle_arbitration");
        jdbcTemplate.update("DELETE FROM observation_bundle_member");
        jdbcTemplate.update("DELETE FROM observation_bundle");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    // ---------- 构造辅助 ----------

    private void createObservation(String requestId, String observationId, String surveyId,
                                   String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("surveyId", surveyId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated());
    }

    private void merge(String requestId, String observationId, int baseVersion,
                       String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isOk());
    }

    private void deleteObservation(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andExpect(status().isOk());
    }

    private Map<String, Object> member(String observationId, Integer baseVersion,
                                       String location, String reading, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        if (baseVersion != null) {
            item.put("baseVersion", baseVersion);
        }
        if (location != null) {
            item.put("location", location);
        }
        if (reading != null) {
            item.put("reading", reading);
        }
        if (note != null) {
            item.put("note", note);
        }
        return item;
    }

    private Map<String, Object> decision(String observationId, String field, String source) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("observationId", observationId);
        item.put("field", field);
        item.put("source", source);
        return item;
    }

    private Map<String, Object> decision(String observationId, String field, String source,
                                         String value, Integer baseVersion) {
        Map<String, Object> item = decision(observationId, field, source);
        if (value != null) {
            item.put("value", value);
        }
        if (baseVersion != null) {
            item.put("baseVersion", baseVersion);
        }
        return item;
    }

    private ResultActions createBundle(String requestId, String bundleKey, String surveyId,
                                       List<String> consistentFields, List<Map<String, Object>> members)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("bundleKey", bundleKey);
        body.put("surveyId", surveyId);
        body.put("consistentFields", consistentFields);
        body.put("members", members);
        body.put("operator", "reviewer-a");
        return mockMvc.perform(post("/api/bundles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions arbitrate(String bundleKey, String requestId,
                                    Map<String, Integer> expectedVersions,
                                    List<Map<String, Object>> decisions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersions", expectedVersions);
        body.put("decisions", decisions);
        body.put("operator", "reviewer-a");
        return mockMvc.perform(post("/api/bundles/{key}/arbitrate", bundleKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int conflictCount(String bundleKey, String status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = ? AND status = ?",
                Integer.class, bundleKey, status);
        return count == null ? 0 : count;
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private String bundleStatus(String bundleKey) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM observation_bundle WHERE bundle_key = ?", String.class, bundleKey);
    }

    // ---------- 建簇 ----------

    @Test
    void createBundleFreezesVersionsAndRegistersConflicts() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点B", "2.0", "备注");

        // obs-a 离线端基于 v1 改 站点C/2.5/远程备注：location、reading 冲突，note 服务端未改可自动合并
        List<Map<String, Object>> members = List.of(
                member("obs-a", 1, "站点C", "2.5", "远程备注"),
                member("obs-b", 1, "站点B", "2.0", "备注"));
        createBundle("req-b1", "bundle-1", "survey-1", List.of(), members)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bundleKey").value("bundle-1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.members[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.members[0].frozenVersion").value(2))
                .andExpect(jsonPath("$.members[1].observationId").value("obs-b"))
                .andExpect(jsonPath("$.conflicts.length()").value(2))
                .andExpect(jsonPath("$.conflicts[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.conflicts[0].field").value("location"))
                .andExpect(jsonPath("$.conflicts[0].type").value("FIELD"))
                .andExpect(jsonPath("$.conflicts[0].status").value("OPEN"))
                .andExpect(jsonPath("$.conflicts[0].localValue").value("站点B"))
                .andExpect(jsonPath("$.conflicts[0].remoteValue").value("站点C"))
                .andExpect(jsonPath("$.conflicts[1].field").value("reading"));

        assertThat(conflictCount("bundle-1", "OPEN")).isEqualTo(2);
        String attached = jdbcTemplate.queryForObject(
                "SELECT open_bundle_key FROM observation_current WHERE observation_id = 'obs-a'", String.class);
        assertThat(attached).isEqualTo("bundle-1");
    }

    @Test
    void createBundleRejectsInvalidShapes() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-b", "survey-2", "站点A", "1.0", "备注");

        // 成员数量少于 2
        createBundle("req-x1", "bundle-x1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isBadRequest());

        // surveyId 不一致
        createBundle("req-x2", "bundle-x2", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-b", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isBadRequest());

        // 重复观测
        createBundle("req-x3", "bundle-x3", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-a", 1, "站点C", "2.0", "备注")))
                .andExpect(status().isBadRequest());

        // 不存在的观测
        createBundle("req-x4", "bundle-x4", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-missing", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isNotFound());

        // 存活成员缺少完整候选值
        createBundle("req-x5", "bundle-x5", "survey-1", List.of(),
                List.of(member("obs-a", null, null, null, null),
                        member("obs-a", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isBadRequest());
        assertThat(bundleStatusOrNull("bundle-x5")).isNull();
    }

    @Test
    void tombstoneCanOnlyEnterAsPendingRestoreItem() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-t", "survey-1", "站点T", "3.0", "墓碑备注");
        deleteObservation("req-3", "obs-t", 1);

        // 墓碑携带候选值：拒绝
        createBundle("req-t1", "bundle-t1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-t", 1, "站点T", "3.0", "墓碑备注")))
                .andExpect(status().isBadRequest());

        // 墓碑作为待恢复项：登记三个 RESTORE 冲突，且无 local/remote/base 候选值
        createBundle("req-t2", "bundle-t2", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"), member("obs-t", null, null, null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conflicts.length()").value(3));
        Integer restoreConflicts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = 'bundle-t2' AND conflict_type = 'RESTORE'",
                Integer.class);
        assertThat(restoreConflicts).isEqualTo(3);
        Integer nonNullCandidates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = 'bundle-t2' "
                        + "AND conflict_type = 'RESTORE' AND "
                        + "(local_value IS NOT NULL OR remote_value IS NOT NULL OR base_value IS NOT NULL)",
                Integer.class);
        assertThat(nonNullCandidates).isZero();
    }

    @Test
    void observationInOpenBundleCannotJoinAnotherBundle() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-b", "survey-1", "站点A", "1.0", "备注");
        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-b", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isCreated());

        // obs-a 已在未结簇 bundle-1 中：重复加入其他簇拒绝
        createBundle("req-b2", "bundle-2", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-b", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isConflict());
        // bundleKey 唯一
        createBundle("req-b3", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-b", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle", Integer.class)).isEqualTo(1);
    }

    @Test
    void createBundleReplaysWhenMembersReordered() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-b", "survey-1", "站点A", "1.0", "备注");

        List<Map<String, Object>> first = List.of(
                member("obs-a", 1, "站点A", "1.0", "备注"),
                member("obs-b", 1, "站点A", "1.0", "备注"));
        List<Map<String, Object>> reordered = List.of(
                member("obs-b", 1, "站点A", "1.0", "备注"),
                member("obs-a", 1, "站点A", "1.0", "备注"));
        createBundle("req-b1", "bundle-1", "survey-1", List.of(), first)
                .andExpect(status().isCreated());
        // 成员换序视为同参：重放首次快照
        createBundle("req-b1", "bundle-1", "survey-1", List.of(), reordered)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bundleKey").value("bundle-1"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_bundle", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-b1'", Integer.class)).isEqualTo(1);

        // 异参 409
        createBundle("req-b1", "bundle-1", "survey-1", List.of("location"), first)
                .andExpect(status().isConflict());
    }

    // ---------- 联合裁决主流程 ----------

    @Test
    void arbitrateResolvesConflictsAutoMergesCleanFieldsAndClosesBundle() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点Z", "9.0", "无冲突备注");

        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点C", "2.5", "远程备注"),
                        member("obs-b", 1, "站点Z", "9.0", "无冲突备注")))
                .andExpect(status().isCreated());

        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("obs-a", 2);
        versions.put("obs-b", 1);
        List<Map<String, Object>> decisions = List.of(
                decision("obs-a", "location", "REMOTE"),
                decision("obs-a", "reading", "REMOTE"));
        arbitrate("bundle-1", "req-a1", versions, decisions)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleKey").value("bundle-1"))
                .andExpect(jsonPath("$.after[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.after[0].version").value(3))
                .andExpect(jsonPath("$.after[0].location").value("站点C"))
                .andExpect(jsonPath("$.after[0].reading").value("2.5"))
                // note 为非冲突字段：服务端未改，按三方规则自动接受离线候选
                .andExpect(jsonPath("$.after[0].note").value("远程备注"))
                // obs-b 无任何变化：不加版本
                .andExpect(jsonPath("$.after[1].observationId").value("obs-b"))
                .andExpect(jsonPath("$.after[1].version").value(1))
                .andExpect(jsonPath("$.conflicts[0].chosenSource").value("REMOTE"))
                .andExpect(jsonPath("$.conflicts[0].chosenValue").value("站点C"))
                .andExpect(jsonPath("$.conflicts[1].field").value("reading"))
                .andExpect(jsonPath("$.conflicts[1].chosenValue").value("2.5"));

        assertThat(versionCount("obs-a")).isEqualTo(3);
        assertThat(versionCount("obs-b")).isEqualTo(1);
        assertThat(conflictCount("bundle-1", "RESOLVED")).isEqualTo(2);
        assertThat(bundleStatus("bundle-1")).isEqualTo("CLOSED");
        // 未结簇挂接解除，观测可以加入新簇
        assertThat(jdbcTemplate.queryForObject(
                "SELECT open_bundle_key FROM observation_current WHERE observation_id = 'obs-a'",
                String.class)).isNull();
        Integer arbitrationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE bundle_key = 'bundle-1'", Integer.class);
        assertThat(arbitrationRows).isEqualTo(1);

        // 簇关闭后再次裁决 409
        arbitrate("bundle-1", "req-a2", versions, decisions).andExpect(status().isConflict());
    }

    @Test
    void arbitrateSupportsLocalBaseAndExplicitSources() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点Z", "9.0", "备注");

        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点C", "2.5", "备注"),
                        member("obs-b", 1, "站点Z", "9.0", "备注")))
                .andExpect(status().isCreated());

        Map<String, Integer> versions = Map.of("obs-a", 2, "obs-b", 1);
        // location 取 LOCAL（站点B），reading 取 BASE（1.0），note 显式新值通过 EXPLICIT 不适用（note 无冲突）
        // 另对 reading 用 EXPLICIT 验证显式新值
        List<Map<String, Object>> decisions = List.of(
                decision("obs-a", "location", "LOCAL"),
                decision("obs-a", "reading", "EXPLICIT", "5.500", null));
        arbitrate("bundle-1", "req-a1", versions, decisions)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after[0].location").value("站点B"))
                // 读数规范化按数值：5.500 存储原文，查询返回 5.500
                .andExpect(jsonPath("$.after[0].reading").value("5.500"))
                .andExpect(jsonPath("$.conflicts[1].chosenSource").value("EXPLICIT"));
    }

    @Test
    void arbitrateBundleWithoutConflictsAcceptsEmptyDecisions() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-b", "survey-1", "站点A", "1.0", "备注");
        createBundle("req-b1", "bundle-1", "survey-1", List.of("location"),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-b", 1, "站点A", "1.0", "备注")))
                .andExpect(status().isCreated());
        assertThat(conflictCount("bundle-1", "OPEN")).isZero();

        arbitrate("bundle-1", "req-a1", Map.of("obs-a", 1, "obs-b", 1), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after[0].version").value(1))
                .andExpect(jsonPath("$.after[1].version").value(1))
                .andExpect(jsonPath("$.conflicts.length()").value(0));
        assertThat(bundleStatus("bundle-1")).isEqualTo("CLOSED");
        assertThat(versionCount("obs-a")).isEqualTo(1);
        assertThat(versionCount("obs-b")).isEqualTo(1);
    }

    // ---------- 墓碑恢复 ----------

    @Test
    void arbitrateRestoresTombstoneWithPerFieldSourcesInSameTransaction() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-t", "survey-1", "站点T", "3.0", "墓碑备注");
        deleteObservation("req-3", "obs-t", 1);

        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-t", null, null, null, null)))
                .andExpect(status().isCreated());

        Map<String, Integer> versions = Map.of("obs-a", 1, "obs-t", 2);
        List<Map<String, Object>> decisions = List.of(
                decision("obs-t", "location", "EXPLICIT", "站点R", null),
                decision("obs-t", "reading", "BASE", null, 1),
                decision("obs-t", "note", "BASE", null, 1));
        arbitrate("bundle-1", "req-a1", versions, decisions)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after[1].observationId").value("obs-t"))
                .andExpect(jsonPath("$.after[1].version").value(3))
                .andExpect(jsonPath("$.after[1].deleted").value(false))
                .andExpect(jsonPath("$.after[1].location").value("站点R"))
                .andExpect(jsonPath("$.after[1].reading").value("3.0"))
                .andExpect(jsonPath("$.after[1].note").value("墓碑备注"));

        // 墓碑复活：当前行存活，历史保留墓碑 v2 与恢复 v3
        mockMvc.perform(get("/api/observations/obs-t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.location").value("站点R"));
        mockMvc.perform(get("/api/observations/obs-t/versions/2"))
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(versionCount("obs-t")).isEqualTo(3);
        Integer restored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM field_conflict WHERE bundle_key = 'bundle-1' "
                        + "AND observation_id = 'obs-t' AND status = 'RESOLVED' "
                        + "AND chosen_source IN ('EXPLICIT','BASE') AND restore_basis IS NOT NULL",
                Integer.class);
        assertThat(restored).isEqualTo(3);
    }

    @Test
    void restoreRejectsLocalRemoteAndMissingRequiredFields() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        createObservation("req-2", "obs-t", "survey-1", "站点T", "3.0", "墓碑备注");
        deleteObservation("req-3", "obs-t", 1);
        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点A", "1.0", "备注"),
                        member("obs-t", null, null, null, null)))
                .andExpect(status().isCreated());

        // 墓碑不能直接提供 LOCAL/REMOTE 候选值
        arbitrate("bundle-1", "req-r1", Map.of("obs-a", 1, "obs-t", 2),
                List.of(decision("obs-t", "location", "LOCAL"),
                        decision("obs-t", "reading", "BASE", null, 1),
                        decision("obs-t", "note", "BASE", null, 1)))
                .andExpect(status().isBadRequest());
        arbitrate("bundle-1", "req-r2", Map.of("obs-a", 1, "obs-t", 2),
                List.of(decision("obs-t", "location", "REMOTE"),
                        decision("obs-t", "reading", "BASE", null, 1),
                        decision("obs-t", "note", "BASE", null, 1)))
                .andExpect(status().isBadRequest());
        // BASE 恢复必须给出存在的非墓碑历史版本
        arbitrate("bundle-1", "req-r3", Map.of("obs-a", 1, "obs-t", 2),
                List.of(decision("obs-t", "location", "EXPLICIT", "站点R", null),
                        decision("obs-t", "reading", "BASE", null, 9),
                        decision("obs-t", "note", "BASE", null, 1)))
                .andExpect(status().isNotFound());
        // 失败后墓碑未复活、簇仍 OPEN、冲突仍 OPEN
        mockMvc.perform(get("/api/observations/obs-t"))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(bundleStatus("bundle-1")).isEqualTo("OPEN");
        assertThat(conflictCount("bundle-1", "OPEN")).isEqualTo(3);
    }

    // ---------- 完整性失败与回滚 ----------

    @Test
    void incompleteOrChangedArbitrationRollsBackEverything() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点Z", "9.0", "备注");
        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点C", "2.5", "备注"),
                        member("obs-b", 1, "站点Z", "9.0", "备注")))
                .andExpect(status().isCreated());

        // 遗漏 reading 冲突
        arbitrate("bundle-1", "req-f1", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE")))
                .andExpect(status().isBadRequest());
        // 多余决定（obs-b 无冲突）
        arbitrate("bundle-1", "req-f2", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "reading", "REMOTE"),
                        decision("obs-b", "note", "LOCAL")))
                .andExpect(status().isBadRequest());
        // 重复选择
        arbitrate("bundle-1", "req-f3", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "location", "LOCAL"),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isBadRequest());
        // expectedVersion 遗漏/多余
        arbitrate("bundle-1", "req-f4", Map.of("obs-a", 2),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isBadRequest());
        // 候选已变化：expectedVersion 不匹配
        arbitrate("bundle-1", "req-f5", Map.of("obs-a", 9, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        // EXPLICIT 缺值 / 非法读数
        arbitrate("bundle-1", "req-f6", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "EXPLICIT", "", null),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isBadRequest());
        arbitrate("bundle-1", "req-f7", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "reading", "EXPLICIT", "1.2345", null)))
                .andExpect(status().isBadRequest());

        // 任一项失败：观测版本、墓碑与冲突状态全部不变，不产生裁决记录
        mockMvc.perform(get("/api/observations/obs-a"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("2.0"));
        assertThat(versionCount("obs-a")).isEqualTo(2);
        assertThat(bundleStatus("bundle-1")).isEqualTo("OPEN");
        assertThat(conflictCount("bundle-1", "OPEN")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration", Integer.class)).isZero();
        // 失败不占键：req-f1 修正为完整请求后成功
        arbitrate("bundle-1", "req-f1", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-f1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void consistentFieldMismatchRejectsWholeArbitration() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点Z", "2.0", "备注");

        createBundle("req-b1", "bundle-1", "survey-1", List.of("location"),
                List.of(member("obs-a", 1, "站点C", "2.5", "备注"),
                        member("obs-b", 1, "站点Z", "2.0", "备注")))
                .andExpect(status().isCreated());

        // obs-a 选 REMOTE 站点C；obs-b 无冲突最终仍为站点Z：一致性破坏，整体 409 回滚
        arbitrate("bundle-1", "req-a1", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "REMOTE"),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isConflict());
        assertThat(bundleStatus("bundle-1")).isEqualTo("OPEN");
        assertThat(conflictCount("bundle-1", "OPEN")).isEqualTo(2);
        mockMvc.perform(get("/api/observations/obs-a"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));

        // 改为 obs-a 取 LOCAL 站点B 仍与站点Z 不一致；显式改成站点Z 后一致并成功
        arbitrate("bundle-1", "req-a2", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "location", "EXPLICIT", "站点Z", null),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/obs-a"))
                .andExpect(jsonPath("$.location").value("站点Z"));
    }

    // ---------- 幂等 ----------

    @Test
    void arbitrationReplaysFirstSnapshotWhenDecisionsReordered() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点Z", "9.0", "备注");
        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点C", "2.5", "备注"),
                        member("obs-b", 1, "站点Z", "9.0", "备注")))
                .andExpect(status().isCreated());

        Map<String, Integer> versions = new LinkedHashMap<>();
        versions.put("obs-a", 2);
        versions.put("obs-b", 1);
        List<Map<String, Object>> decisions = List.of(
                decision("obs-a", "location", "REMOTE"),
                decision("obs-a", "reading", "REMOTE"));
        List<Map<String, Object>> reordered = List.of(
                decision("obs-a", "reading", "REMOTE"),
                decision("obs-a", "location", "REMOTE"));

        arbitrate("bundle-1", "req-a1", versions, decisions).andExpect(status().isOk());
        // 字段项换序 + expectedVersions 换序：同参重放首次快照，不再生成版本或裁决记录
        Map<String, Integer> versionsReordered = new LinkedHashMap<>();
        versionsReordered.put("obs-b", 1);
        versionsReordered.put("obs-a", 2);
        arbitrate("bundle-1", "req-a1", versionsReordered, reordered)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after[0].version").value(3));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bundle_arbitration WHERE request_id = 'req-a1'", Integer.class))
                .isEqualTo(1);
        assertThat(versionCount("obs-a")).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-a1'", Integer.class)).isEqualTo(1);

        // 异参（改选择）409
        arbitrate("bundle-1", "req-a1", versions,
                List.of(decision("obs-a", "location", "LOCAL"),
                        decision("obs-a", "reading", "REMOTE")))
                .andExpect(status().isConflict());
    }

    // ---------- 证据查询 ----------

    @Test
    void evidenceQueriesAreReadOnlyAndStablyOrdered() throws Exception {
        createObservation("req-1", "obs-a", "survey-1", "站点A", "1.0", "备注");
        merge("req-2", "obs-a", 1, "站点B", "2.0", "备注");
        createObservation("req-3", "obs-b", "survey-1", "站点Z", "9.0", "备注");
        createBundle("req-b1", "bundle-1", "survey-1", List.of(),
                List.of(member("obs-a", 1, "站点C", "2.5", "备注"),
                        member("obs-b", 1, "站点Z", "9.0", "备注")))
                .andExpect(status().isCreated());
        arbitrate("bundle-1", "req-a1", Map.of("obs-a", 2, "obs-b", 1),
                List.of(decision("obs-a", "reading", "REMOTE"),
                        decision("obs-a", "location", "REMOTE")))
                .andExpect(status().isOk());

        // 簇证据：成员按观测、冲突按观测+字段稳定排序
        mockMvc.perform(get("/api/bundles/{key}", "bundle-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.members[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.members[1].observationId").value("obs-b"))
                .andExpect(jsonPath("$.conflicts[0].observationId").value("obs-a"))
                .andExpect(jsonPath("$.conflicts[0].field").value("location"))
                .andExpect(jsonPath("$.conflicts[1].field").value("reading"));

        // 裁决证据：簇级前后快照与逐字段来源
        mockMvc.perform(get("/api/bundles/arbitrations/{requestId}", "req-a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundleKey").value("bundle-1"))
                .andExpect(jsonPath("$.before[0].version").value(2))
                .andExpect(jsonPath("$.after[0].version").value(3))
                .andExpect(jsonPath("$.conflicts[0].field").value("location"))
                .andExpect(jsonPath("$.conflicts[0].chosenSource").value("REMOTE"));

        mockMvc.perform(get("/api/bundles/{key}", "bundle-missing"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bundles/arbitrations/{requestId}", "req-missing"))
                .andExpect(status().isNotFound());
    }

    private String bundleStatusOrNull(String bundleKey) {
        List<String> statuses = jdbcTemplate.query(
                "SELECT status FROM observation_bundle WHERE bundle_key = ?",
                (rs, rowNum) -> rs.getString("status"), bundleKey);
        return statuses.isEmpty() ? null : statuses.get(0);
    }
}
