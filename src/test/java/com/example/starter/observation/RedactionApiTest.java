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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测附件脱敏 API 测试：主流程、失败分支、事务原子性、幂等、撤销/清除、证据固化不回写与诊断统计。
 * 使用真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RedactionApiTest {

    private static final Instant T1 = Instant.parse("2026-09-22T10:15:30Z");
    private static final Instant T2 = Instant.parse("2026-09-22T11:15:30Z");

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
        jdbcTemplate.update("DELETE FROM snapshot_attachment_ref");
        jdbcTemplate.update("DELETE FROM attachment_redaction");
        jdbcTemplate.update("DELETE FROM redaction_batch");
        jdbcTemplate.update("DELETE FROM observation_attachment");
        jdbcTemplate.update("DELETE FROM redaction_rule");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(T1);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ---------- 辅助构造 ----------

    private ResultActions createObservation(String requestId, String observationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions registerRule(String role, String code, int version, String name) throws Exception {
        Map<String, Object> body = Map.of("ruleCode", code, "ruleVersion", version, "ruleName", name);
        return mockMvc.perform(post("/api/redaction-rules")
                .header("X-Actor-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revokeRule(String code, int version) throws Exception {
        return mockMvc.perform(post("/api/redaction-rules/{code}/versions/{version}/revoke", code, version)
                .header("X-Actor-Role", "OFFICER"));
    }

    private ResultActions registerAttachment(String role, String observationId, String attachmentId,
                                             String summary) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attachmentId", attachmentId);
        body.put("fileName", "记录-" + attachmentId + ".txt");
        body.put("contentType", "text/plain");
        body.put("sizeBytes", 1024);
        body.put("originalSummary", summary);
        return mockMvc.perform(post("/api/observations/{id}/attachments", observationId)
                .header("X-Actor-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> redactionItem(String attachmentId, String code, int version,
                                              String redacted, String note) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("attachmentId", attachmentId);
        item.put("ruleCode", code);
        item.put("ruleVersion", version);
        item.put("redactedSummary", redacted);
        item.put("replacementNote", note);
        return item;
    }

    private ResultActions createRedactions(String role, String observationId, String requestId, String batchId,
                                           List<Map<String, Object>> items) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("batchId", batchId);
        body.put("operator", "officer-a");
        body.put("reason", "对外展示需隐藏个人信息");
        body.put("items", items);
        return mockMvc.perform(post("/api/observations/{id}/redactions", observationId)
                .header("X-Actor-Role", role)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions revokeRedaction(String redactionId, String requestId, String reason) throws Exception {
        Map<String, Object> body = Map.of("requestId", requestId, "reason", reason, "operator", "officer-a");
        return mockMvc.perform(post("/api/redactions/{id}/revoke", redactionId)
                .header("X-Actor-Role", "OFFICER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions clearContent(String attachmentId, String requestId) throws Exception {
        Map<String, Object> body = Map.of("requestId", requestId, "reason", "保留期届满", "operator", "officer-a");
        return mockMvc.perform(post("/api/attachments/{id}/clear", attachmentId)
                .header("X-Actor-Role", "OFFICER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int count(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
        return count == null ? 0 : count;
    }

    private String firstRedactionId(String attachmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT redaction_id FROM attachment_redaction WHERE attachment_id = ? "
                        + "ORDER BY attachment_version ASC LIMIT 1", String.class, attachmentId);
    }

    private void setupObservationWithAttachment() throws Exception {
        createObservation("req-o1", "obs-1").andExpect(status().isCreated());
        registerAttachment("FIELD_ENGINEER", "obs-1", "att-1", "张三身份证110101199001011234，温度36.7度")
                .andExpect(status().isCreated());
        registerRule("OFFICER", "PII", 1, "个人信息脱敏v1").andExpect(status().isCreated());
    }

    // ---------- 附件登记 ----------

    @Test
    void registerAttachmentSucceedsAndRejectsBadStates() throws Exception {
        createObservation("req-a1", "obs-1").andExpect(status().isCreated());

        registerAttachment("FIELD_ENGINEER", "obs-1", "att-1", "摘要")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attachmentId").value("att-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.contentCleared").value(false))
                .andExpect(jsonPath("$.clearedAtUtc").doesNotExist());

        // 重复附件标识 409
        registerAttachment("FIELD_ENGINEER", "obs-1", "att-1", "摘要")
                .andExpect(status().isConflict());

        // 观测不存在 404；墓碑观测 422
        registerAttachment("FIELD_ENGINEER", "obs-x", "att-2", "摘要")
                .andExpect(status().isNotFound());
        Map<String, Object> deleteBody = Map.of("requestId", "req-a2", "expectedVersion", 1);
        mockMvc.perform(post("/api/observations/obs-1/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteBody)))
                .andExpect(status().isOk());
        registerAttachment("FIELD_ENGINEER", "obs-1", "att-3", "摘要")
                .andExpect(status().isUnprocessableEntity());

        // 未授权角色 403：现场接口拒绝证据审阅人；缺失角色头同样 403
        registerAttachment("EVIDENCE_READER", "obs-x", "att-4", "摘要")
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/observations/obs-x/attachments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "attachmentId", "att-5", "fileName", "f.txt", "contentType", "text/plain",
                                "sizeBytes", 1, "originalSummary", "摘要"))))
                .andExpect(status().isForbidden());
    }

    // ---------- 脱敏主流程 ----------

    @Test
    void redactionBatchCreatesIncrementingVersionsAndPublicShowsLatestOnly() throws Exception {
        setupObservationWithAttachment();
        registerRule("OFFICER", "PII", 2, "个人信息脱敏v2").andExpect(status().isCreated());

        createRedactions("OFFICER", "obs-1", "req-r1", "batch-1", List.of(
                        redactionItem("att-1", "PII", 1, "张三身份证***，温度36.7度", "身份证整体替换为***")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.createdAtUtc").value(T1.toString()))
                .andExpect(jsonPath("$.versions[0].attachmentVersion").value(1))
                .andExpect(jsonPath("$.versions[0].publicActive").value(true));

        // 用另一规则版本再脱敏生成 v2
        createRedactions("OFFICER", "obs-1", "req-r2", "batch-2", List.of(
                        redactionItem("att-1", "PII", 2, "张三身份证110101********1234，温度36.7度", "保留前六后四")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].attachmentVersion").value(2));

        // 公开查询只返回最新有效版本，且不含原始摘要
        mockMvc.perform(get("/api/attachments/att-1/redactions/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentVersion").value(2))
                .andExpect(jsonPath("$.redactedSummary").value("张三身份证110101********1234，温度36.7度"))
                .andExpect(jsonPath("$.revokedAtUtc").doesNotExist());

        // 观测级公开查询
        mockMvc.perform(get("/api/observations/obs-1/redactions/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$[0].attachmentVersion").value(2));

        // 证据查询可同时读原始摘要与全部版本（含撤销字段位）
        mockMvc.perform(get("/api/attachments/att-1/evidence").header("X-Actor-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalSummary").value("张三身份证110101199001011234，温度36.7度"))
                .andExpect(jsonPath("$.redactionVersions.length()").value(2))
                .andExpect(jsonPath("$.redactionVersions[0].attachmentVersion").value(1))
                .andExpect(jsonPath("$.redactionVersions[1].attachmentVersion").value(2));

        assertThat(count("attachment_redaction", "batch_id", "batch-1")).isEqualTo(1);
    }

    @Test
    void redactionBatchWithMultipleAttachmentsAllOrNothing() throws Exception {
        createObservation("req-b1", "obs-1").andExpect(status().isCreated());
        registerAttachment("FIELD_ENGINEER", "obs-1", "att-1", "摘要1").andExpect(status().isCreated());
        registerAttachment("FIELD_ENGINEER", "obs-1", "att-2", "摘要2").andExpect(status().isCreated());
        registerRule("OFFICER", "PII", 1, "v1").andExpect(status().isCreated());

        // 两个附件一次批量提交，全部成功
        createRedactions("OFFICER", "obs-1", "req-b2", "batch-ok", List.of(
                        redactionItem("att-1", "PII", 1, "脱敏1", "替换1"),
                        redactionItem("att-2", "PII", 1, "脱敏2", "替换2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].attachmentId").value("att-1"))
                .andExpect(jsonPath("$.versions[1].attachmentId").value("att-2"));

        // 第二条引用未登记规则版本：整批失败，不产生任何版本
        registerRule("OFFICER", "LOC", 9, "不存在版本无关，使用未登记版本号").andExpect(status().isCreated());
        createRedactions("OFFICER", "obs-1", "req-b3", "batch-fail", List.of(
                        redactionItem("att-1", "PII", 1, "再次脱敏1", "替换"),
                        redactionItem("att-2", "PII", 7, "坏条目", "替换")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(count("attachment_redaction", "batch_id", "batch-fail")).isZero();
        // att-1 没有因为失败批次多出版本；同附件同规则版本未被占用，失败不占键后可成功
        Integer att1Versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment_redaction WHERE attachment_id = 'att-1'", Integer.class);
        assertThat(att1Versions).isEqualTo(1);
        assertThat(count("request_log", "request_id", "req-b3")).isZero();
    }

    // ---------- 失败分支：403 / 404 / 422 ----------

    @Test
    void redactionValidationFailuresAreCategorized() throws Exception {
        setupObservationWithAttachment();
        registerAttachment("FIELD_ENGINEER", "obs-2", "att-other", "别的观测的附件");
        createObservation("req-v0", "obs-2").andExpect(status().isCreated());
        registerAttachment("FIELD_ENGINEER", "obs-2", "att-2", "摘要2").andExpect(status().isCreated());

        // 附件不属于该观测：403
        createRedactions("OFFICER", "obs-1", "req-v1", "batch-x1", List.of(
                        redactionItem("att-2", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isForbidden());

        // 附件不存在：404
        createRedactions("OFFICER", "obs-1", "req-v2", "batch-x2", List.of(
                        redactionItem("att-nope", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isNotFound());

        // 规则版本未登记：422
        createRedactions("OFFICER", "obs-1", "req-v3", "batch-x3", List.of(
                        redactionItem("att-1", "PII", 5, "脱敏", "替换")))
                .andExpect(status().isUnprocessableEntity());

        // 规则版本已撤销：422；先成功用 v1 脱敏一次，再撤销规则版本，换新附件引用已撤销版本
        createRedactions("OFFICER", "obs-1", "req-v4", "batch-x4", List.of(
                        redactionItem("att-1", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isOk());
        revokeRule("PII", 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.revokedAtUtc").value(T1.toString()));
        registerAttachment("FIELD_ENGINEER", "obs-1", "att-3", "摘要3").andExpect(status().isCreated());
        createRedactions("OFFICER", "obs-1", "req-v5", "batch-x5", List.of(
                        redactionItem("att-3", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isUnprocessableEntity());

        // 同附件同一规则版本重复使用：422（重新登记新规则版本给 att-3 用，确保失败原因单一）
        createRedactions("OFFICER", "obs-1", "req-v6", "batch-x6", List.of(
                        redactionItem("att-1", "PII", 1, "再来一次", "替换")))
                .andExpect(status().isUnprocessableEntity());

        // 未授权角色：403
        createRedactions("FIELD_ENGINEER", "obs-1", "req-v7", "batch-x7", List.of(
                        redactionItem("att-1", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isForbidden());

        // 墓碑观测：422
        mockMvc.perform(post("/api/observations/obs-1/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requestId", "req-v8", "expectedVersion", 1))))
                .andExpect(status().isOk());
        createRedactions("OFFICER", "obs-1", "req-v9", "batch-x9", List.of(
                        redactionItem("att-1", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isUnprocessableEntity());

        // 观测不存在：404
        createRedactions("OFFICER", "obs-nope", "req-v10", "batch-x10", List.of(
                        redactionItem("att-1", "PII", 1, "脱敏", "替换")))
                .andExpect(status().isNotFound());
    }

    // ---------- 撤销 ----------

    @Test
    void revokeOnlyRemovesPublicEligibilityAndKeepsHistory() throws Exception {
        setupObservationWithAttachment();
        createRedactions("OFFICER", "obs-1", "req-k1", "batch-k1", List.of(
                redactionItem("att-1", "PII", 1, "身份证***", "整体替换"))).andExpect(status().isOk());
        String redactionId = firstRedactionId("att-1");

        revokeRedaction(redactionId, "req-k2", "规则用错，撤回公开资格")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicActive").value(false))
                .andExpect(jsonPath("$.revokedAtUtc").value(T1.toString()))
                .andExpect(jsonPath("$.revokeReason").value("规则用错，撤回公开资格"))
                .andExpect(jsonPath("$.revokedBy").value("officer-a"))
                .andExpect(jsonPath("$.redactedSummary").value("身份证***"));

        // 公开查询不再返回（404）；观测级公开列表为空
        mockMvc.perform(get("/api/attachments/att-1/redactions/public"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/obs-1/redactions/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 证据查询仍能读到撤销版本及其理由、撤销时刻
        mockMvc.perform(get("/api/redactions/{id}/evidence", redactionId).header("X-Actor-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicActive").value(false))
                .andExpect(jsonPath("$.revokeReason").value("规则用错，撤回公开资格"));

        // 重复撤销 422
        revokeRedaction(redactionId, "req-k3", "再撤一次")
                .andExpect(status().isUnprocessableEntity());

        // 撤销不存在的版本 404；证据接口无权限 403
        revokeRedaction("rd-nope", "req-k4", "x").andExpect(status().isNotFound());
        mockMvc.perform(get("/api/redactions/{id}/evidence", redactionId))
                .andExpect(status().isForbidden());
    }

    @Test
    void revokingLatestExposesPreviousActiveVersionAgain() throws Exception {
        setupObservationWithAttachment();
        registerRule("OFFICER", "PII", 2, "v2").andExpect(status().isCreated());
        createRedactions("OFFICER", "obs-1", "req-p1", "batch-p1", List.of(
                redactionItem("att-1", "PII", 1, "脱敏v1", "替换1"))).andExpect(status().isOk());
        createRedactions("OFFICER", "obs-1", "req-p2", "batch-p2", List.of(
                redactionItem("att-1", "PII", 2, "脱敏v2", "替换2"))).andExpect(status().isOk());
        String latestId = jdbcTemplate.queryForObject(
                "SELECT redaction_id FROM attachment_redaction WHERE attachment_id = 'att-1' "
                        + "ORDER BY attachment_version DESC LIMIT 1", String.class);

        revokeRedaction(latestId, "req-p3", "v2 有问题").andExpect(status().isOk());

        // v2 撤销后公开查询回到仍有效的 v1
        mockMvc.perform(get("/api/attachments/att-1/redactions/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentVersion").value(1))
                .andExpect(jsonPath("$.redactedSummary").value("脱敏v1"));
    }

    // ---------- 物理清除 ----------

    @Test
    void clearContentMakesOriginalUnrecoverableButKeepsEvidenceSnapshots() throws Exception {
        setupObservationWithAttachment();
        createRedactions("OFFICER", "obs-1", "req-c1", "batch-c1", List.of(
                redactionItem("att-1", "PII", 1, "身份证***", "整体替换"))).andExpect(status().isOk());

        // 先在合并快照中固化原始摘要与脱敏摘要
        Map<String, Object> refOriginal = new LinkedHashMap<>();
        refOriginal.put("attachmentId", "att-1");
        refOriginal.put("kind", "ORIGINAL");
        Map<String, Object> refRedacted = new LinkedHashMap<>();
        refRedacted.put("attachmentId", "att-1");
        refRedacted.put("kind", "REDACTED");
        refRedacted.put("redactionId", firstRedactionId("att-1"));
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-c2");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "备注");
        mergeBody.put("attachmentRefs", List.of(refOriginal, refRedacted));
        mockMvc.perform(post("/api/observations/obs-1/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 物理清除原始内容
        clearContent("att-1", "req-c3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentCleared").value(true))
                .andExpect(jsonPath("$.clearedAtUtc").value(T1.toString()));

        // 重复清除 422
        clearContent("att-1", "req-c4").andExpect(status().isUnprocessableEntity());

        // 证据视图：原始摘要为 null 且带清除标记；脱敏版本仍在
        mockMvc.perform(get("/api/attachments/att-1/evidence").header("X-Actor-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalSummary").doesNotExist())
                .andExpect(jsonPath("$.contentCleared").value(true))
                .andExpect(jsonPath("$.redactionVersions.length()").value(1));

        // 已固化快照摘要不受清除影响（旧证据不可回写）
        mockMvc.perform(get("/api/observations/obs-1/snapshot-refs").header("X-Actor-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("ORIGINAL"))
                .andExpect(jsonPath("$[0].summaryText").value("张三身份证110101199001011234，温度36.7度"))
                .andExpect(jsonPath("$[0].contentClearedAtFix").value(false))
                .andExpect(jsonPath("$[1].kind").value("REDACTED"))
                .andExpect(jsonPath("$[1].summaryText").value("身份证***"));

        // 清除后无法再固化 ORIGINAL 证据（422），但仍可引用脱敏版本
        Map<String, Object> mergeBody2 = new LinkedHashMap<>();
        mergeBody2.put("requestId", "req-c5");
        mergeBody2.put("baseVersion", 2);
        mergeBody2.put("location", "站点B");
        mergeBody2.put("reading", "2.0");
        mergeBody2.put("note", "备注");
        mergeBody2.put("attachmentRefs", List.of(refOriginal));
        mockMvc.perform(post("/api/observations/obs-1/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody2)))
                .andExpect(status().isUnprocessableEntity());

        // 撤销脱敏后原始内容也不能恢复
        revokeRedaction(firstRedactionId("att-1"), "req-c6", "撤回").andExpect(status().isOk());
        mockMvc.perform(get("/api/attachments/att-1/evidence").header("X-Actor-Role", "EVIDENCE_READER"))
                .andExpect(jsonPath("$.contentCleared").value(true))
                .andExpect(jsonPath("$.originalSummary").doesNotExist());
    }

    // ---------- 证据固化不被后续脱敏追溯 ----------

    @Test
    void laterRedactionDoesNotRewriteSnapshotEvidence() throws Exception {
        setupObservationWithAttachment();
        createRedactions("OFFICER", "obs-1", "req-e1", "batch-e1", List.of(
                redactionItem("att-1", "PII", 1, "脱敏v1", "替换1"))).andExpect(status().isOk());
        String v1Id = firstRedactionId("att-1");

        Map<String, Object> refRedacted = new LinkedHashMap<>();
        refRedacted.put("attachmentId", "att-1");
        refRedacted.put("kind", "REDACTED");
        refRedacted.put("redactionId", v1Id);
        Map<String, Object> mergeBody = new LinkedHashMap<>();
        mergeBody.put("requestId", "req-e2");
        mergeBody.put("baseVersion", 1);
        mergeBody.put("location", "站点B");
        mergeBody.put("reading", "1.0");
        mergeBody.put("note", "备注");
        mergeBody.put("attachmentRefs", List.of(refRedacted));
        mockMvc.perform(post("/api/observations/obs-1/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mergeBody)))
                .andExpect(status().isOk());

        // 引用非法：REDACTED 缺 redactionId 400；ORIGINAL 带 redactionId 400；
        // 脱敏版本不属于该附件 403；脱敏版本不存在 404
        Map<String, Object> bad1 = new LinkedHashMap<>();
        bad1.put("attachmentId", "att-1");
        bad1.put("kind", "REDACTED");
        mergeWithRefs("req-e3", 2, List.of(bad1)).andExpect(status().isBadRequest());

        Map<String, Object> bad2 = new LinkedHashMap<>();
        bad2.put("attachmentId", "att-1");
        bad2.put("kind", "ORIGINAL");
        bad2.put("redactionId", v1Id);
        mergeWithRefs("req-e4", 2, List.of(bad2)).andExpect(status().isBadRequest());

        createObservation("req-e5", "obs-2").andExpect(status().isCreated());
        registerAttachment("FIELD_ENGINEER", "obs-2", "att-2", "摘要2").andExpect(status().isCreated());
        createRedactions("OFFICER", "obs-2", "req-e6", "batch-e2", List.of(
                redactionItem("att-2", "PII", 1, "别的附件脱敏", "替换"))).andExpect(status().isOk());
        String otherId = firstRedactionId("att-2");
        Map<String, Object> bad3 = new LinkedHashMap<>();
        bad3.put("attachmentId", "att-1");
        bad3.put("kind", "REDACTED");
        bad3.put("redactionId", otherId);
        mergeWithRefs("req-e7", 2, List.of(bad3)).andExpect(status().isForbidden());

        Map<String, Object> bad4 = new LinkedHashMap<>();
        bad4.put("attachmentId", "att-1");
        bad4.put("kind", "REDACTED");
        bad4.put("redactionId", "rd-nope");
        mergeWithRefs("req-e8", 2, List.of(bad4)).andExpect(status().isNotFound());

        // 后续再脱敏 v2 并撤销 v1：快照引用仍指向 v1 的原文与版本号
        registerRule("OFFICER", "PII", 2, "v2").andExpect(status().isCreated());
        createRedactions("OFFICER", "obs-1", "req-e9", "batch-e3", List.of(
                redactionItem("att-1", "PII", 2, "脱敏v2", "替换2"))).andExpect(status().isOk());
        revokeRedaction(v1Id, "req-e10", "撤回v1").andExpect(status().isOk());

        mockMvc.perform(get("/api/observations/obs-1/snapshot-refs").header("X-Actor-Role", "EVIDENCE_READER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceType").value("MERGE"))
                .andExpect(jsonPath("$[0].redactionId").value(v1Id))
                .andExpect(jsonPath("$[0].redactionVersion").value(1))
                .andExpect(jsonPath("$[0].summaryText").value("脱敏v1"));
    }

    private ResultActions mergeWithRefs(String requestId, int baseVersion, List<Map<String, Object>> refs)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点B");
        body.put("reading", "2.0");
        body.put("note", "备注");
        body.put("attachmentRefs", refs);
        return mockMvc.perform(post("/api/observations/obs-1/merge")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    // ---------- 幂等 ----------

    @Test
    void redactionIdempotencyReplaysSameParamsAndRejectsDifferentParams() throws Exception {
        setupObservationWithAttachment();
        List<Map<String, Object>> items = List.of(
                redactionItem("att-1", "PII", 1, "脱敏", "替换"));

        createRedactions("OFFICER", "obs-1", "req-i1", "batch-i1", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].attachmentVersion").value(1));
        // 同参重放：完整原响应，不产生新版本
        createRedactions("OFFICER", "obs-1", "req-i1", "batch-i1", items)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].attachmentVersion").value(1));
        assertThat(count("attachment_redaction", "request_id", "req-i1")).isEqualTo(1);

        // 异参 409（换批次标识）
        createRedactions("OFFICER", "obs-1", "req-i1", "batch-other", items)
                .andExpect(status().isConflict());
        assertThat(count("request_log", "request_id", "req-i1")).isEqualTo(1);

        // 跨请求复用同一 batchId（即使 requestId 不同）409，且不产生新版本
        createRedactions("OFFICER", "obs-1", "req-i1b", "batch-i1", items)
                .andExpect(status().isConflict());
        assertThat(count("redaction_batch", "batch_id", "batch-i1")).isEqualTo(1);
        assertThat(count("attachment_redaction", "batch_id", "batch-i1")).isEqualTo(1);

        // 撤销幂等：同参重放
        String redactionId = firstRedactionId("att-1");
        revokeRedaction(redactionId, "req-i2", "撤销理由").andExpect(status().isOk());
        revokeRedaction(redactionId, "req-i2", "撤销理由")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicActive").value(false));
        // 撤销异参（不同理由）409
        revokeRedaction(redactionId, "req-i2", "别的理由").andExpect(status().isConflict());

        // 物理清除幂等：同参重放返回清除时刻，异参 409
        clearContent("att-1", "req-i3").andExpect(status().isOk());
        clearContent("att-1", "req-i3").andExpect(status().isOk());
        clearContent("att-1", "req-i3").andExpect(status().isOk());
    }

    // ---------- 诊断 ----------

    @Test
    void diagnosticsReportCountsAndDurationsWithHalfOpenUtcWindow() throws Exception {
        setupObservationWithAttachment();
        registerRule("OFFICER", "PII", 2, "v2").andExpect(status().isCreated());

        // T1 生成第一个批次
        Mockito.when(clock.instant()).thenReturn(T1);
        createRedactions("OFFICER", "obs-1", "req-d1", "batch-d1", List.of(
                redactionItem("att-1", "PII", 1, "脱敏1", "替换1"))).andExpect(status().isOk());
        // T2 生成第二个版本并撤销第一个
        Mockito.when(clock.instant()).thenReturn(T2);
        createRedactions("OFFICER", "obs-1", "req-d2", "batch-d2", List.of(
                redactionItem("att-1", "PII", 2, "脱敏2", "替换2"))).andExpect(status().isOk());
        revokeRedaction(firstRedactionId("att-1"), "req-d3", "撤回v1").andExpect(status().isOk());

        Instant windowFrom = Instant.parse("2026-09-22T10:00:00Z");
        Instant windowTo = Instant.parse("2026-09-22T12:00:00Z");
        mockMvc.perform(get("/api/redactions/diagnostics")
                        .header("X-Actor-Role", "OFFICER")
                        .param("from", windowFrom.toString())
                        .param("to", windowTo.toString())
                        .param("requiredCount", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualCreatedCount").value(2))
                .andExpect(jsonPath("$.actualRevokedCount").value(1))
                .andExpect(jsonPath("$.requiredCount").value(5))
                .andExpect(jsonPath("$.countGap").value(-3))
                .andExpect(jsonPath("$.requiredDurationSeconds").value(7200))
                .andExpect(jsonPath("$.firstCreatedAtUtc").value(T1.toString()))
                .andExpect(jsonPath("$.lastCreatedAtUtc").value(T2.toString()))
                .andExpect(jsonPath("$.actualDurationSeconds").value(3600))
                .andExpect(jsonPath("$.durationGapSeconds").value(-3600));

        // 左闭右开：to=T1 时不含 T1 的版本
        mockMvc.perform(get("/api/redactions/diagnostics")
                        .header("X-Actor-Role", "OFFICER")
                        .param("from", windowFrom.toString())
                        .param("to", T1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualCreatedCount").value(0))
                .andExpect(jsonPath("$.firstCreatedAtUtc").doesNotExist())
                .andExpect(jsonPath("$.actualDurationSeconds").value(0));

        // from >= to 400；无角色 403
        mockMvc.perform(get("/api/redactions/diagnostics")
                        .header("X-Actor-Role", "OFFICER")
                        .param("from", T2.toString())
                        .param("to", windowFrom.toString()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/redactions/diagnostics")
                        .param("from", windowFrom.toString())
                        .param("to", windowTo.toString()))
                .andExpect(status().isForbidden());
    }
}
