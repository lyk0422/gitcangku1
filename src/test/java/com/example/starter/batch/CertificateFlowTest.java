package com.example.starter.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合格证声明匹配与放行门禁的真实 H2 数据库测试：
 * 发布/撤回、单批与批量绑定、放行固化快照、拆分继承、合批重新校验、
 * 左闭右开 UTC 边界、失败整事务回滚、commandKey 幂等与并发提交顺序裁决。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateFlowTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UtcClock clock;

    @BeforeEach
    void cleanAndFixClock() {
        jdbc.update("DELETE FROM release_certificate_snapshot");
        jdbc.update("DELETE FROM batch_merge_lineage");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM declaration_prefix");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
        clock.setFixed(T0);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ---------- 主流程：发布→绑定→放行固化→撤回只影响后续 ----------

    @Test
    void publishBindRelease_snapshotFrozen_andWithdrawalOnlyAffectsFuture() throws Exception {
        String material = "MAT-MAIN-" + unique();
        String declKey = publishDeclaration("DECL-1-" + unique(), material, 10,
                T0.minus(Duration.ofDays(30)), T0.plus(Duration.ofDays(30)),
                List.of("LOT-1"), 201);

        String batch = "BK-MAIN-" + unique();
        createBatch(batch, material, "LOT-1-001", List.of("t1"));
        passTestsAndFirstApproval(batch, "insp-1", "qa-1");
        assertEquals("RELEASE_REVIEW", currentStatus(batch));

        // 未绑定声明：第二批准 422，状态不变，无放行快照
        approve(batch, "ops-1", "OPERATIONS", "CK-OPS-1", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batch));
        assertEquals(0, snapshotCount(batch));

        // 失败不占键：绑定后用同一 commandKey 重试第二批准成功
        bindOne(batch, declKey, "CK-BIND-1", 201);
        approve(batch, "ops-1", "OPERATIONS", "CK-OPS-1", 201);
        assertEquals("RELEASED", currentStatus(batch));

        JsonNode history = history(batch);
        JsonNode snapshots = history.path("releaseCertificateSnapshots");
        assertEquals(1, snapshots.size());
        JsonNode snap = snapshots.get(0);
        assertEquals(declKey, snap.path("declarationKey").asText());
        assertEquals(1, snap.path("version").asInt());
        assertEquals("LOT-1", snap.path("matchedPrefix").asText());
        assertEquals(material, snap.path("materialCode").asText());
        assertEquals(T0.toString(), snap.path("releasedAt").asText());
        assertEquals(T0.minus(Duration.ofDays(30)).toString(), snap.path("validFrom").asText());

        String snapshotJson = snapshots.toString();

        // 撤回声明：已放行批次历史快照不变
        withdraw(declKey, "supplier-1", "声明更正", "CK-WD-1", 200);
        JsonNode afterWithdraw = history(batch).path("releaseCertificateSnapshots");
        assertEquals(snapshotJson, afterWithdraw.toString(), "撤回不得改写历史放行证据");

        // 撤回只影响后续放行：新批次绑定/放行被拦截
        String batch2 = "BK-MAIN2-" + unique();
        createBatch(batch2, material, "LOT-1-002", List.of("t1"));
        passTestsAndFirstApproval(batch2, "insp-2", "qa-2");
        bindOne(batch2, declKey, "CK-BIND-2", 422);
    }

    // ---------- 发布失败分支 ----------

    @Test
    void publish_invalidInterval_returns422_andDoesNotOccupyKey() throws Exception {
        String material = "MAT-IV-" + unique();
        // 终点不晚于起点
        publishDeclaration("DECL-IV-" + unique(), material, 10, T0, T0, List.of("LOT"), 422);
        publishDeclaration("DECL-IV2-" + unique(), material, 10, T0.plusSeconds(1), T0,
                List.of("LOT"), 422);
        // 请求内前缀重复
        publishDeclaration("DECL-IV3-" + unique(), material, 10,
                T0.minusSeconds(1), T0.plusSeconds(1), List.of("LOT", "LOT"), 422);
        // 失败不占键：同 commandKey 修正为合法参数后成功
        String key = "DECL-IVOK-" + unique();
        String body = publishBody("CK-PUB-IV", key, material, 10,
                T0.minusSeconds(1), T0.plusSeconds(1), List.of("LOT"));
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-PUB-IV", key, material, 10,
                                T0, T0, List.of("LOT"))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void publish_samePriorityOverlap422_differentPriorityAllowed_andVersionIncrements()
            throws Exception {
        String material = "MAT-OV-" + unique();
        String v1Key = "DECL-OV-1-" + unique();
        publishDeclaration(v1Key, material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-1", "LOT-2"), 201);
        // 同优先级前缀重叠 → 422
        publishDeclaration("DECL-OV-2-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-2"), 422);
        // 不同优先级允许重叠 → 成功，版本 2
        String d3 = publishDeclaration("DECL-OV-3-" + unique(), material, 20,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-2"), 201);
        JsonNode d3Node = declaration(d3);
        assertEquals(2, d3Node.path("version").asInt());
        // 撤回 v1 后，同优先级 10 的 ACTIVE 声明不再占用 LOT-2，可再发布同前缀版本
        withdraw(v1Key, "s", "版本作废", "CK-WD-OV", 200);
        String v3 = publishDeclaration("DECL-OV-4-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-2"), 201);
        assertEquals(3, declaration(v3).path("version").asInt());
        // 已撤回声明不能重复撤回 → 409
        withdraw(v1Key, "s", "再次撤回", "CK-WD-OV2", 409);
    }

    @Test
    void publish_idempotentReplay_andChangedParams409_andDuplicateKey409() throws Exception {
        String material = "MAT-IDEM-" + unique();
        String key = "DECL-IDEM-" + unique();
        String body = publishBody("CK-PUB-IDEM", key, material, 10,
                T0.minusSeconds(1), T0.plusSeconds(1), List.of("LOT-1", "LOT-2"));
        MvcResult first = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 同键改参（区间不同）→ 409
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-PUB-IDEM", key, material, 10,
                                T0.minusSeconds(2), T0.plusSeconds(1), List.of("LOT-1", "LOT-2"))))
                .andExpect(status().isConflict());
        // 同键改操作者 → 409（指纹包含操作者）
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup-other")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        // 声明业务键重复 → 409
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-PUB-OTHER", key, material, 10,
                                T0.minusSeconds(1), T0.plusSeconds(1), List.of("LOT-9"))))
                .andExpect(status().isConflict());
        // 仅一条版本落库
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = ?",
                Integer.class, key));
    }

    // ---------- 绑定失败分支：物料 / 前缀 / 时间窗边界 ----------

    @Test
    void bind_materialMismatch_returns422() throws Exception {
        String m1 = "MAT-MM1-" + unique();
        String m2 = "MAT-MM2-" + unique();
        String decl = publishDeclaration("DECL-MM-" + unique(), m1, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT"), 201);
        String batch = "BK-MM-" + unique();
        createBatch(batch, m2, "LOT-1", List.of("t1"));
        mockMvc.perform(post("/api/batches/" + batch + "/certificate-bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BIND-MM\",\"declarationKey\":\"" + decl + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, bindingCount(batch));
    }

    @Test
    void bind_prefixMismatch_returns422() throws Exception {
        String material = "MAT-PM-" + unique();
        String decl = publishDeclaration("DECL-PM-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-A"), 201);
        String batch = "BK-PM-" + unique();
        createBatch(batch, material, "LOT-B-1", List.of("t1"));
        MvcResult result = bindOneRaw(batch, decl, "CK-BIND-PM", 422);
        assertEquals("PREFIX_MISMATCH", objectMapper.readTree(
                result.getResponse().getContentAsString()).path("code").asText());
        assertEquals(0, bindingCount(batch));
    }

    @Test
    void bind_windowIsLeftClosedRightOpen() throws Exception {
        String material = "MAT-WIN-" + unique();
        Instant from = T0;
        Instant to = T0.plus(Duration.ofHours(1));
        String decl = publishDeclaration("DECL-WIN-" + unique(), material, 10, from, to,
                List.of("LOT"), 201);

        // 恰在起点（含）绑定成功
        String atFrom = "BK-WIN-F-" + unique();
        createBatch(atFrom, material, "LOT-1", List.of("t1"));
        bindOne(atFrom, decl, "CK-WIN-F", 201);

        // 推进到终点（不含）：绑定 422 EXPIRED
        clock.setFixed(to);
        String atTo = "BK-WIN-T-" + unique();
        createBatch(atTo, material, "LOT-2", List.of("t1"));
        MvcResult expired = bindOneRaw(atTo, decl, "CK-WIN-T", 422);
        assertEquals("DECLARATION_EXPIRED", objectMapper.readTree(
                expired.getResponse().getContentAsString()).path("code").asText());

        // 起点前一秒：NOT_YET_VALID
        clock.setFixed(from.minusSeconds(1));
        String before = "BK-WIN-B-" + unique();
        createBatch(before, material, "LOT-3", List.of("t1"));
        MvcResult early = bindOneRaw(before, decl, "CK-WIN-B", 422);
        assertEquals("DECLARATION_NOT_YET_VALID", objectMapper.readTree(
                early.getResponse().getContentAsString()).path("code").asText());

        // 终点前一秒：成功
        clock.setFixed(to.minusSeconds(1));
        String inside = "BK-WIN-I-" + unique();
        createBatch(inside, material, "LOT-4", List.of("t1"));
        bindOne(inside, decl, "CK-WIN-I", 201);
    }

    // ---------- 批量绑定：任一失效整次回滚、无半成品 ----------

    @Test
    void bulkBind_oneInvalid_rollsBackAll_andFailureDistinguishesReason() throws Exception {
        String material = "MAT-BULK-" + unique();
        String goodDecl = publishDeclaration("DECL-GOOD-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT"), 201);
        String withdrawnDecl = publishDeclaration("DECL-WD-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("OLD"), 201);

        String b1 = "BK-BULK-1-" + unique();
        String b2 = "BK-BULK-2-" + unique();
        String b3 = "BK-BULK-3-" + unique();
        createBatch(b1, material, "LOT-1", List.of("t1"));
        createBatch(b2, material, "LOT-2", List.of("t1"));
        createBatch(b3, material, "OLD-1", List.of("t1"));

        String body = bulkBindBody("CK-BULK-1", List.of(
                new String[]{b1, goodDecl}, new String[]{b2, goodDecl},
                new String[]{b3, withdrawnDecl}));
        // 第三条声明在批量提交前被撤回，制造运行期失效：整次回滚且原因可区分
        withdraw(withdrawnDecl, "s", "临时撤回", "CK-WD-BULK", 200);
        MvcResult result = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        assertEquals("DECLARATION_WITHDRAWN", objectMapper.readTree(
                result.getResponse().getContentAsString()).path("code").asText());

        // 整次回滚：三个批次都查不到半成品绑定
        assertEquals(0, bindingCount(b1));
        assertEquals(0, bindingCount(b2));
        assertEquals(0, bindingCount(b3));
        // 失败不占键：修正为全部有效声明后同键成功
        String body2 = bulkBindBody("CK-BULK-1", List.of(
                new String[]{b1, goodDecl}, new String[]{b2, goodDecl},
                new String[]{b3, goodDecl}));
        // b3 批号 OLD-1 不命中 LOT，先用一个命中 LOT 的批次替换：新建 b4
        String b4 = "BK-BULK-4-" + unique();
        createBatch(b4, material, "LOT-4", List.of("t1"));
        body2 = bulkBindBody("CK-BULK-1", List.of(
                new String[]{b1, goodDecl}, new String[]{b2, goodDecl},
                new String[]{b4, goodDecl}));
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
                .andExpect(status().isCreated());
        assertEquals(1, bindingCount(b1));
        assertEquals(1, bindingCount(b2));
        assertEquals(1, bindingCount(b4));
    }

    @Test
    void bulkBind_idempotentReplay_andChangedSetConflicts() throws Exception {
        String material = "MAT-BIDEM-" + unique();
        String decl = publishDeclaration("DECL-BIDEM-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT"), 201);
        String b1 = "BK-BIDEM-1-" + unique();
        String b2 = "BK-BIDEM-2-" + unique();
        createBatch(b1, material, "LOT-1", List.of("t1"));
        createBatch(b2, material, "LOT-2", List.of("t1"));
        String body = bulkBindBody("CK-BULK-IDEM", List.of(
                new String[]{b1, decl}, new String[]{b2, decl}));
        MvcResult first = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding", Integer.class));
        // 同键异参（集合变化：去掉 b2）→ 409
        String changed = bulkBindBody("CK-BULK-IDEM",
                List.<String[]>of(new String[]{b1, decl}));
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    // ---------- 拆分继承 ----------

    @Test
    void split_childrenInheritBindings_andCanReleaseWithInheritedReference() throws Exception {
        String material = "MAT-SPLIT-" + unique();
        String decl = publishDeclaration("DECL-SPLIT-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-P"), 201);
        String parent = "BK-SP-P-" + unique();
        createBatch(parent, material, "LOT-P-1", List.of("t1"));
        bindOne(parent, decl, "CK-SP-BIND", 201);
        releaseFromQuarantine(parent, "insp-p", "qa-p", "ops-p");

        String c1 = "BK-SP-C1-" + unique();
        String c2 = "BK-SP-C2-" + unique();
        split(parent, "CK-SPLIT", List.of(new String[]{c1, "LOT-P-10"},
                new String[]{c2, "LOT-P-11"}), 201);

        JsonNode bindings = bindingsOf(c1);
        assertEquals(1, bindings.size());
        assertEquals(decl, bindings.get(0).path("declarationKey").asText());
        assertEquals(parent, bindings.get(0).path("inheritedFrom").asText(),
                "拆分产物继承来源应为父批");

        // 子批重新检验/批准，凭继承引用通过门禁并固化自己的快照
        releaseFromQuarantine(c1, "insp-c", "qa-c", "ops-c");
        assertEquals("RELEASED", currentStatus(c1));
        JsonNode snap = history(c1).path("releaseCertificateSnapshots");
        assertEquals(1, snap.size());
        assertEquals(decl, snap.get(0).path("declarationKey").asText());
    }

    // ---------- 合批：多父必须重新校验全部物料 ----------

    @Test
    void merge_childMustRebindAllParentMaterials_thenReleaseSnapshotsAll() throws Exception {
        String m1 = "MAT-MG1-" + unique();
        String m2 = "MAT-MG2-" + unique();
        String d1 = publishDeclaration("DECL-MG1-" + unique(), m1, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-1"), 201);
        String d2 = publishDeclaration("DECL-MG2-" + unique(), m2, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-2"), 201);

        String p1 = "BK-MG-P1-" + unique();
        String p2 = "BK-MG-P2-" + unique();
        createBatch(p1, m1, "LOT-1-1", List.of("t1", "t2"));
        createBatch(p2, m2, "LOT-2-1", List.of("t2", "t3"));
        bindOne(p1, d1, "CK-MG-B1", 201);
        bindOne(p2, d2, "CK-MG-B2", 201);
        releaseFromQuarantine(p1, "i1", "q1", "o1");
        releaseFromQuarantine(p2, "i2", "q2", "o2");

        String child = "BK-MG-C-" + unique();
        merge(child, "LOT-MG-1", "BLEND", List.of(p1, p2), "CK-MERGE", 201);
        assertEquals("MERGED", currentStatus(p1));
        assertEquals("MERGED", currentStatus(p2));

        // 诊断：要求 2 个物料，实际 0，差额 2，ready=false
        JsonNode diag = diagnostics(child);
        assertFalse(diag.path("ready").asBoolean());
        assertEquals(2, diag.path("requiredMaterialCount").asInt());
        assertEquals(0, diag.path("actualBindingCount").asInt());
        assertEquals(2, diag.path("missingBindingCount").asInt());
        assertEquals(2, diag.path("missingMaterials").size());

        // 合批产物不继承绑定：放行 422
        submitTest(child, "t1", "PASS", "i3", 201);
        submitTest(child, "t2", "PASS", "i3", 201);
        submitTest(child, "t3", "PASS", "i3", 201);
        approve(child, "q3", "QUALITY", "CK-MG-A1", 201);
        approve(child, "o3", "OPERATIONS", "CK-MG-A2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(child));

        // 只绑一个父物料仍 422；诊断差额变为 1
        bindOne(child, d1, "CK-MG-B3", 201);
        approve(child, "o3", "OPERATIONS", "CK-MG-A2", 422);
        JsonNode diag2 = diagnostics(child);
        assertEquals(1, diag2.path("missingBindingCount").asInt());
        assertEquals(m2, diag2.path("missingMaterials").get(0).asText());

        // 补齐第二个父物料后放行；放行失败未占键 CK-MG-A2
        bindOne(child, d2, "CK-MG-B4", 201);
        approve(child, "o3", "OPERATIONS", "CK-MG-A2", 201);
        assertEquals("RELEASED", currentStatus(child));
        JsonNode snaps = history(child).path("releaseCertificateSnapshots");
        assertEquals(2, snaps.size(), "合批产物按全部父物料各固化一行快照");
        assertEquals(m1, snaps.get(0).path("materialCode").asText(), "快照按物料字典序");
        assertEquals(m2, snaps.get(1).path("materialCode").asText());
    }

    @Test
    void merge_nonReleasedOrUnknownParent_rollsBack() throws Exception {
        String m1 = "MAT-MF1-" + unique();
        String decl = publishDeclaration("DECL-MF-" + unique(), m1, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-1"), 201);
        String p1 = "BK-MF-P1-" + unique();
        String p2 = "BK-MF-P2-" + unique();
        createBatch(p1, m1, "LOT-1-1", List.of("t1")); // 仍 QUARANTINED
        createBatch(p2, m1, "LOT-1-2", List.of("t1"));
        bindOne(p2, decl, "CK-MF-B", 201);
        releaseFromQuarantine(p2, "i", "q", "o");

        String child = "BK-MF-C-" + unique();
        // 一个父批未放行 → 409，整次回滚，无产物、无血缘、父批状态不变
        merge(child, "LOT-MF-1", "BLEND", List.of(p1, p2), "CK-MF", 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge_lineage", Integer.class));
        assertEquals("QUARANTINED", currentStatus(p1));
        assertEquals("RELEASED", currentStatus(p2));
        // 不存在父批 → 404
        merge(child, "LOT-MF-2", "BLEND", List.of(p2, "NO-SUCH-PARENT"), "CK-MF2", 404);
    }

    // ---------- 诊断差额字段 ----------

    @Test
    void diagnostics_reportsActualRequiredAndDelta() throws Exception {
        String material = "MAT-DG-" + unique();
        Instant from = T0.minus(Duration.ofHours(2));
        Instant to = T0.plus(Duration.ofHours(1));
        String decl = publishDeclaration("DECL-DG-" + unique(), material, 10, from, to,
                List.of("LOT"), 201);
        String batch = "BK-DG-" + unique();
        createBatch(batch, material, "LOT-1", List.of("t1"));
        bindOne(batch, decl, "CK-DG-B", 201);

        JsonNode diag = diagnostics(batch);
        assertTrue(diag.path("ready").asBoolean());
        assertEquals(1, diag.path("requiredMaterialCount").asInt());
        assertEquals(1, diag.path("actualBindingCount").asInt());
        assertEquals(0, diag.path("missingBindingCount").asInt());
        JsonNode item = diag.path("items").get(0);
        assertEquals("OK", item.path("reasonCode").asText());
        assertTrue(item.path("materialMatches").asBoolean());
        assertTrue(item.path("prefixMatches").asBoolean());
        assertTrue(item.path("activeAtEvaluatedAt").asBoolean());
        // 差额：evaluatedAt - validFrom = 7200 秒（要求 >=0）；validTo - evaluatedAt = 3600 秒（要求 >0）
        assertEquals(7200L, item.path("windowStartDeltaSeconds").asLong());
        assertEquals(3600L, item.path("windowEndDeltaSeconds").asLong());
        assertEquals(T0.toString(), diag.path("evaluatedAt").asText());
    }

    // ---------- 并发：撤回与最终放行按提交顺序裁决 ----------

    @Test
    void concurrentWithdrawAndFinalRelease_commitOrderDecides() throws Exception {
        String material = "MAT-RACE-" + unique();
        String decl = publishDeclaration("DECL-RACE-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(60), List.of("LOT"), 201);
        String batch = "BK-RACE-" + unique();
        createBatch(batch, material, "LOT-1", List.of("t1"));
        bindOne(batch, decl, "CK-RACE-B", 201);
        submitTest(batch, "t1", "PASS", "insp", 201);
        approve(batch, "qa", "QUALITY", "CK-RACE-A1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batch + "/approvals")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RACE-A2\"}")),
                () -> callStatus(post("/api/certificates/declarations/" + decl + "/withdraw")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-RACE-W\",\"reason\":\"并发撤回\"}"))
        );

        int approval = results.get(0).get(30, TimeUnit.SECONDS);
        int withdraw = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(200, withdraw, "ACTIVE 声明撤回应成功（或被并发幂等重放）");
        if (approval == 201) {
            // 放行先提交：RELEASED 且快照固化；撤回随后生效但不改写快照
            assertEquals("RELEASED", currentStatus(batch));
            assertEquals(1, snapshotCount(batch));
            assertEquals("WITHDRAWN", declaration(decl).path("status").asText());
        } else {
            // 撤回先提交：门禁读到 WITHDRAWN，422，停留 RELEASE_REVIEW，无快照
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batch));
            assertEquals(0, snapshotCount(batch));
        }
    }

    @Test
    void concurrentPublishSameMaterial_versionsAreSequential() throws Exception {
        String material = "MAT-RACE-PUB-" + unique();
        String body1 = publishBody("CK-RP-1", "DECL-RP-1-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-1"));
        String body2 = publishBody("CK-RP-2", "DECL-RP-2-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT-2"));
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON).content(body1)),
                () -> callStatus(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup")
                        .contentType(MediaType.APPLICATION_JSON).content(body2))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM certificate_declaration WHERE material_code = ? ORDER BY version",
                Integer.class, material);
        assertEquals(List.of(1, 2), versions, "同物料并发发布版本必须连续且唯一");
    }

    @Test
    void merge_lineageQueries_unifySplitAndMergeParents() throws Exception {
        String material = "MAT-LN-" + unique();
        String decl = publishDeclaration("DECL-LN-" + unique(), material, 10,
                T0.minusSeconds(10), T0.plusSeconds(10), List.of("LOT"), 201);
        String p1 = "BK-LN-P1-" + unique();
        String p2 = "BK-LN-P2-" + unique();
        createBatch(p1, material, "LOT-1", List.of("t1"));
        createBatch(p2, material, "LOT-2", List.of("t1"));
        bindOne(p1, decl, "CK-LN-B1", 201);
        bindOne(p2, decl, "CK-LN-B2", 201);
        releaseFromQuarantine(p1, "i1", "q1", "o1");
        releaseFromQuarantine(p2, "i2", "q2", "o2");

        String child = "BK-LN-C-" + unique();
        merge(child, "LOT-C-1", "BLEND", List.of(p1, p2), "CK-LN-M", 201);

        MvcResult ancestorsResult = mockMvc.perform(get("/api/batches/" + child + "/ancestors"))
                .andExpect(status().isOk()).andReturn();
        JsonNode ancestors = objectMapper.readTree(
                ancestorsResult.getResponse().getContentAsString());
        assertEquals(2, ancestors.size(), "合批产物应有两个父批祖先");
        assertEquals(p1, ancestors.get(0).path("batchKey").asText());
        assertEquals(p2, ancestors.get(1).path("batchKey").asText(), "祖先按合批请求 seq 排序");

        MvcResult descendantsResult = mockMvc.perform(get("/api/batches/" + p1 + "/descendants"))
                .andExpect(status().isOk()).andReturn();
        JsonNode descendants = objectMapper.readTree(
                descendantsResult.getResponse().getContentAsString());
        assertEquals(1, descendants.size());
        assertEquals(child, descendants.get(0).path("batchKey").asText());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String publishBody(String commandKey, String declarationKey, String material,
                               int priority, Instant from, Instant to, List<String> prefixes)
            throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("commandKey", commandKey);
        node.put("declarationKey", declarationKey);
        node.put("materialCode", material);
        node.put("priority", priority);
        node.put("validFrom", from.toString());
        node.put("validTo", to.toString());
        node.putPOJO("allowedBatchNoPrefixes", prefixes);
        return objectMapper.writeValueAsString(node);
    }

    private String publishDeclaration(String declarationKey, String material, int priority,
                                      Instant from, Instant to, List<String> prefixes,
                                      int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-PUB-" + unique(), declarationKey, material,
                                priority, from, to, prefixes)))
                .andExpect(status().is(expectedStatus));
        return declarationKey;
    }

    private void withdraw(String declarationKey, String actor, String reason, String commandKey,
                          int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/certificates/declarations/" + declarationKey + "/withdraw")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"" + reason
                                + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private JsonNode declaration(String declarationKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/certificates/declarations/" + declarationKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void createBatch(String batchKey, String material, String batchNo, List<String> tests)
            throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("commandKey", "CK-CREATE-" + unique());
        node.put("batchKey", batchKey);
        node.put("productCode", material);
        node.put("batchNo", batchNo);
        node.put("producedAt", T0.minus(Duration.ofDays(1)).toString());
        node.putPOJO("requiredTests", tests);
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().isCreated());
    }

    private void submitTest(String batchKey, String item, String result, String inspector,
                            int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TestCmd(
                                "CK-T-" + unique(), "TK-" + unique(), item, result, inspector))))
                .andExpect(status().is(expected));
    }

    private record TestCmd(String commandKey, String testKey, String testItem, String result,
                           String inspector) {
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void bindOne(String batchKey, String declKey, String commandKey, int expected)
            throws Exception {
        bindOneRaw(batchKey, declKey, commandKey, expected);
    }

    private MvcResult bindOneRaw(String batchKey, String declKey, String commandKey, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "operator-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"declarationKey\":\""
                                + declKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private String bulkBindBody(String commandKey, List<String[]> pairs) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("commandKey", commandKey);
        var arr = node.putArray("bindings");
        for (String[] pair : pairs) {
            var item = arr.addObject();
            item.put("batchKey", pair[0]);
            item.put("declarationKey", pair[1]);
        }
        return objectMapper.writeValueAsString(node);
    }

    private JsonNode bindingsOf(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/certificates/bindings/" + batchKey))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode diagnostics(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/batches/" + batchKey + "/certificate-diagnostics"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode history(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String currentStatus(String batchKey) throws Exception {
        return history(batchKey).path("batch").path("status").asText();
    }

    private int snapshotCount(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_certificate_snapshot WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private int bindingCount(String batchKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey);
    }

    private void passTestsAndFirstApproval(String batchKey, String inspector, String qa)
            throws Exception {
        JsonNode batch = history(batchKey);
        batch.path("batch").path("requiredTests").forEach(
                t -> {
                    try {
                        submitTest(batchKey, t.asText(), "PASS", inspector, 201);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        approve(batchKey, qa, "QUALITY", "CK-Q-" + unique(), 201);
    }

    /**
     * 从隔离态走完检验、双角色批准进入 RELEASED（调用前需已绑定合格证）。
     */
    private void releaseFromQuarantine(String batchKey, String inspector, String qa, String ops)
            throws Exception {
        passTestsAndFirstApproval(batchKey, inspector, qa);
        approve(batchKey, ops, "OPERATIONS", "CK-O-" + unique(), 201);
    }

    private void split(String parent, String commandKey, List<String[]> children, int expected)
            throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("commandKey", commandKey);
        var arr = node.putArray("children");
        for (String[] child : children) {
            arr.addObject().put("batchKey", child[0]).put("batchNo", child[1]);
        }
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().is(expected));
    }

    private void merge(String childKey, String childBatchNo, String childProduct,
                       List<String> parents, String commandKey, int expected) throws Exception {
        var node = objectMapper.createObjectNode();
        node.put("commandKey", commandKey);
        node.put("childBatchKey", childKey);
        node.put("childBatchNo", childBatchNo);
        node.put("childProductCode", childProduct);
        var arr = node.putArray("parents");
        for (String parent : parents) {
            arr.addObject().put("batchKey", parent);
        }
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "merger-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(node)))
                .andExpect(status().is(expected));
    }

    private int callStatus(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
        try {
            return mockMvc.perform(req).andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SafeVarargs
    private List<Future<Integer>> runConcurrent(Callable<Integer>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        CyclicBarrier barrier = new CyclicBarrier(tasks.length);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return task.call();
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        return futures;
    }
}
