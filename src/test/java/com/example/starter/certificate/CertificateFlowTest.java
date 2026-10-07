package com.example.starter.certificate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * 合格证声明与批次绑定端到端测试：发布/撤回/绑定/放行门禁主流程、422/409/404 分支、
 * 批量绑定整次回滚、commandKey 幂等、并发裁决、撤回不改写历史、拆分继承、诊断查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM merge_lineage");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 主流程 ----------

    @Test
    void happyFlow_releaseSnapshotFrozen_andWithdrawDoesNotRewriteHistory() throws Exception {
        String decl = "DECL-H-" + unique();
        publish(decl, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String batchKey = "BK-CERT-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);
        bind("CK-B-" + unique(), List.<String[]>of(new String[]{batchKey, decl, "1"}), 201);

        submitPass(batchKey, "t1", "insp-1");
        approve(batchKey, "qa-1", "QUALITY", 201);
        approve(batchKey, "ops-1", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        // 放行快照固化声明版本、匹配前缀与时点
        JsonNode cert = certificate(batchKey);
        assertEquals(decl, cert.path("releaseSnapshot").path("declarationKey").asText());
        assertEquals(1, cert.path("releaseSnapshot").path("declarationVersion").asInt());
        assertEquals("LOT", cert.path("releaseSnapshot").path("matchedPrefix").asText());
        assertFalse(cert.path("releaseSnapshot").path("releasedAt").isNull());
        assertEquals(decl, cert.path("binding").path("declarationKey").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class,
                batchKey));
        String snapshotBefore = certificate(batchKey).path("releaseSnapshot").toString();

        // 撤回只影响后续放行，不改写历史快照与绑定
        withdraw(decl, 1, "CK-W-" + unique(), 201);
        JsonNode certAfter = certificate(batchKey);
        assertEquals(snapshotBefore, certAfter.path("releaseSnapshot").toString());
        assertEquals(decl, certAfter.path("binding").path("declarationKey").asText());
        JsonNode history = history(batchKey);
        assertEquals(decl, history.path("releaseSnapshot").path("declarationKey").asText());
        assertEquals(decl, history.path("binding").path("declarationKey").asText());
        assertEquals("RELEASED", history.path("batch").path("status").asText());

        // 撤回后声明明细可查且状态为 WITHDRAWN，版本历史稳定
        MvcResult detail = mockMvc.perform(get("/api/certificates/declarations/" + decl))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = objectMapper.readTree(detail.getResponse().getContentAsString())
                .path("versions");
        assertEquals(1, versions.size());
        assertEquals("WITHDRAWN", versions.get(0).path("status").asText());
        assertFalse(versions.get(0).path("withdrawnAt").isNull());
    }

    // ---------- 发布失败分支 ----------

    @Test
    void publishInvalidInterval_returns422_andFailureDoesNotOccupyKey() throws Exception {
        String decl = "DECL-IV-" + unique();
        // 起点等于终点 → 422；起点晚于终点 → 422
        publish("CK-IV-1", decl, 1, "PROD-1", "LOT", 1,
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 422);
        publish(decl, 2, "PROD-1", "LOT", 1,
                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z", 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = ?",
                Integer.class, decl));
        // 失败不占键：同一 commandKey 修正参数后成功
        publish("CK-IV-1", decl, 1, "PROD-1", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
    }

    @Test
    void publishOverlappingPrefix_samePriority422_differentPriorityAllowed() throws Exception {
        String declA = "DECL-OV-A-" + unique();
        String declB = "DECL-OV-B-" + unique();
        String declC = "DECL-OV-C-" + unique();
        publish(declA, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 前缀 LO 与 LOT 重叠且优先级相同 → 422
        publish(declB, 1, "PROD-1", "LO", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 422);
        // 优先级不同 → 允许
        publish(declC, 1, "PROD-1", "LO", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 同一声明键的新版本不参与跨声明重叠判定 → 允许
        publish(declA, 2, "PROD-1", "LO", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 同声明键同版本重复发布 → 409
        publish(declA, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 409);
        // 不同物料互不影响
        publish(declB, 1, "PROD-2", "LO", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 缺 X-Actor-Id → 400
        mockMvc.perform(post("/api/certificates/declarations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-NA", "DECL-NA-" + unique(), 1, "PROD-1", "LOT", 9,
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 绑定失败分支与批量回滚 ----------

    @Test
    void bindFailures_materialPrefixWithdrawnNotFoundAndDuplicate() throws Exception {
        String batchKey = "BK-BF-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);

        // 物料不匹配 → 422
        String declM = "DECL-BF-M-" + unique();
        publish(declM, 1, "PROD-9", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind("CK-BF-1", List.<String[]>of(new String[]{batchKey, declM, "1"}), 422);

        // 批号前缀不匹配 → 422
        String declP = "DECL-BF-P-" + unique();
        publish(declP, 1, "PROD-1", "XX", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind("CK-BF-2", List.<String[]>of(new String[]{batchKey, declP, "1"}), 422);

        // 声明已撤回（失效）→ 422
        String declW = "DECL-BF-W-" + unique();
        publish(declW, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        withdraw(declW, 1, "CK-BF-W1", 201);
        bind("CK-BF-3", List.<String[]>of(new String[]{batchKey, declW, "1"}), 422);

        // 批次不存在 → 404；声明不存在 → 404
        bind("CK-BF-4", List.<String[]>of(new String[]{"NO-SUCH-BATCH", declP, "1"}), 404);
        bind("CK-BF-5", List.<String[]>of(new String[]{batchKey, "NO-SUCH-DECL", "1"}), 404);

        // 上述失败均不产生绑定
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));

        // 成功绑定后，重复绑定（不同 commandKey）→ 409
        String declOk = "DECL-BF-OK-" + unique();
        publish(declOk, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind("CK-BF-6", List.<String[]>of(new String[]{batchKey, declOk, "1"}), 201);
        bind("CK-BF-7", List.<String[]>of(new String[]{batchKey, declOk, "1"}), 409);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void bulkBind_anyInvalidDeclarationRollsBackEntirely() throws Exception {
        String b1 = "BK-BB1-" + unique();
        String b2 = "BK-BB2-" + unique();
        createBatch(b1, "LOT-1", List.of("t1"), 201);
        createBatch(b2, "LOT-2", List.of("t1"), 201);
        String declOk = "DECL-BB-OK-" + unique();
        String declW = "DECL-BB-W-" + unique();
        publish(declOk, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(declW, 1, "PROD-1", "LOT", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        withdraw(declW, 1, "CK-BB-W", 201);

        // 任一失效声明 → 整次 422 回滚，查询不到半成品
        bind("CK-BB-1", List.<String[]>of(new String[]{b1, declOk, "1"}, new String[]{b2, declW, "1"}), 422);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_certificate_binding",
                Integer.class));

        // 失败不占键：同一 commandKey 修正参数后整次成功
        MvcResult ok = bind("CK-BB-1",
                List.<String[]>of(new String[]{b1, declOk, "1"}, new String[]{b2, declOk, "1"}), 201);
        JsonNode body = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertEquals(2, body.path("boundCount").asInt());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM batch_certificate_binding",
                Integer.class));
    }

    // ---------- 放行门禁 ----------

    @Test
    void releaseGate_unboundBatch_returns422() throws Exception {
        String batchKey = "BK-GATE-NB-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);
        submitPass(batchKey, "t1", "insp-1");
        approve(batchKey, "qa-1", "QUALITY", 201);
        // 未绑定合格证声明 → 第二批准（放行）422，停留 RELEASE_REVIEW
        approve(batchKey, "ops-1", "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class,
                batchKey));
    }

    @Test
    void releaseGate_declarationInvalidAtReleaseTime_returns422() throws Exception {
        // 已过期声明：绑定时不校验区间，放行时校验 → 422
        String expired = "DECL-EXP-" + unique();
        publish(expired, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z",
                201);
        String b1 = "BK-GATE-EXP-" + unique();
        createBatch(b1, "LOT-1", List.of("t1"), 201);
        bind("CK-G-1", List.<String[]>of(new String[]{b1, expired, "1"}), 201);
        submitPass(b1, "t1", "insp-1");
        approve(b1, "qa-1", "QUALITY", 201);
        approve(b1, "ops-1", "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b1));

        // 尚未生效声明 → 422
        String future = "DECL-FUT-" + unique();
        publish(future, 1, "PROD-1", "LOT", 2, "2035-01-01T00:00:00Z", "2040-01-01T00:00:00Z",
                201);
        String b2 = "BK-GATE-FUT-" + unique();
        createBatch(b2, "LOT-2", List.of("t1"), 201);
        bind("CK-G-2", List.<String[]>of(new String[]{b2, future, "1"}), 201);
        submitPass(b2, "t1", "insp-1");
        approve(b2, "qa-1", "QUALITY", 201);
        approve(b2, "ops-1", "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b2));

        // 绑定后声明被撤回 → 放行 422
        String decl = "DECL-RW-" + unique();
        publish(decl, 1, "PROD-1", "LOT", 3, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String b3 = "BK-GATE-RW-" + unique();
        createBatch(b3, "LOT-3", List.of("t1"), 201);
        bind("CK-G-3", List.<String[]>of(new String[]{b3, decl, "1"}), 201);
        submitPass(b3, "t1", "insp-1");
        approve(b3, "qa-1", "QUALITY", 201);
        withdraw(decl, 1, "CK-G-W", 201);
        approve(b3, "ops-1", "OPERATIONS", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(b3));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot",
                Integer.class));
    }

    // ---------- 幂等 ----------

    @Test
    void commandKey_publishBindWithdraw_replayAndConflict() throws Exception {
        String decl = "DECL-IDEM-" + unique();
        String publishBody = publishBody("CK-P-1", decl, 1, "PROD-1", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z");
        MvcResult first = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(publishBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = ?",
                Integer.class, decl));
        // 同 commandKey 改参（前缀不同）→ 409
        mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-P-1", decl, 1, "PROD-1", "LOTX", 1,
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z")))
                .andExpect(status().isConflict());

        // 绑定重放
        String batchKey = "BK-IDEM-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);
        String bindBody = bindBody("CK-B-1", List.<String[]>of(new String[]{batchKey, decl, "1"}));
        MvcResult bindFirst = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "planner")
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult bindReplay = mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "planner")
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(bindFirst.getResponse().getContentAsString(),
                bindReplay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));
        // 同 commandKey 换操作人 → 409（指纹含操作者）
        mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "other-planner")
                        .contentType(MediaType.APPLICATION_JSON).content(bindBody))
                .andExpect(status().isConflict());

        // 撤回重放与异参 409
        String withdrawBody = "{\"commandKey\":\"CK-W-1\",\"reason\":\"供应商更正\"}";
        MvcResult wFirst = mockMvc.perform(post("/api/certificates/declarations/" + decl
                        + "/versions/1/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody))
                .andExpect(status().isCreated()).andReturn();
        MvcResult wReplay = mockMvc.perform(post("/api/certificates/declarations/" + decl
                        + "/versions/1/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(withdrawBody))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(wFirst.getResponse().getContentAsString(),
                wReplay.getResponse().getContentAsString());
        mockMvc.perform(post("/api/certificates/declarations/" + decl + "/versions/1/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-W-1\",\"reason\":\"另一个原因\"}"))
                .andExpect(status().isConflict());
        // 不同 commandKey 重复撤回 → 409（状态冲突）
        withdraw(decl, 1, "CK-W-2", 409);
    }

    // ---------- 并发 ----------

    @Test
    void concurrentWithdrawAndFinalApproval_commitOrderDecides() throws Exception {
        String decl = "DECL-CW-" + unique();
        publish(decl, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String batchKey = "BK-CW-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);
        bind("CK-CW-B", List.<String[]>of(new String[]{batchKey, decl, "1"}), 201);
        submitPass(batchKey, "t1", "insp-1");
        approve(batchKey, "qa-1", "QUALITY", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-A\"}")),
                () -> callStatus(post("/api/certificates/declarations/" + decl
                                + "/versions/1/withdraw")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-W\",\"reason\":\"质量复核\"}"))
        );
        int approval = results.get(0).get(30, TimeUnit.SECONDS);
        int withdraw = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, withdraw, "撤回始终可提交，只影响后续放行");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?", Integer.class,
                batchKey);
        if (approval == 201) {
            // 放行先提交：快照已固化，撤回不改写
            assertEquals("RELEASED", currentStatus(batchKey));
            assertEquals(1, snapshots);
        } else {
            // 撤回先提交：放行读取到已撤回声明 → 422，停留 RELEASE_REVIEW，无快照
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
            assertEquals(0, snapshots);
        }
    }

    @Test
    void concurrentBindSameBatch_onlyOneSucceeds() throws Exception {
        String decl1 = "DECL-CB1-" + unique();
        String decl2 = "DECL-CB2-" + unique();
        publish(decl1, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(decl2, 1, "PROD-1", "LOT", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String batchKey = "BK-CB-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "p1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-CB-1",
                                List.<String[]>of(new String[]{batchKey, decl1, "1"})))),
                () -> callStatus(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "p2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody("CK-CB-2",
                                List.<String[]>of(new String[]{batchKey, decl2, "1"}))))
        );
        int ok = 0;
        int conflict = 0;
        for (Future<Integer> f : results) {
            int code = f.get(30, TimeUnit.SECONDS);
            if (code == 201) {
                ok++;
            } else if (code == 409) {
                conflict++;
            } else {
                fail("意外状态码: " + code);
            }
        }
        assertEquals(1, ok);
        assertEquals(1, conflict);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void concurrentPublishSameCommandKey_sameWinnerForBoth() throws Exception {
        String decl = "DECL-CP-" + unique();
        String body = publishBody("CK-CP-1", decl, 1, "PROD-1", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z");
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "sup-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_declaration WHERE declaration_key = ?",
                Integer.class, decl));
    }

    // ---------- 拆分继承与诊断 ----------

    @Test
    void splitChildren_inheritBinding_andCanReleaseWithInheritedReference() throws Exception {
        String decl = "DECL-INH-" + unique();
        publish(decl, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String parent = "BK-INH-P-" + unique();
        createBatch(parent, "LOT-1", List.of("t1"), 201);
        bind("CK-INH-B", List.<String[]>of(new String[]{parent, decl, "1"}), 201);
        submitPass(parent, "t1", "insp-1");
        approve(parent, "qa-1", "QUALITY", 201);
        approve(parent, "ops-1", "OPERATIONS", 201);

        String child = "BK-INH-C-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-INH-S\",\"children\":[{\"batchKey\":\""
                                + child + "\",\"batchNo\":\"LOT-C1\"},{\"batchKey\":\"BK-INH-C2-"
                                + unique() + "\",\"batchNo\":\"LOT-C2\"}]}"))
                .andExpect(status().isCreated());

        // 子批继承父批绑定引用（同一声明版本与匹配前缀）
        JsonNode cert = certificate(child);
        assertEquals(decl, cert.path("binding").path("declarationKey").asText());
        assertEquals(1, cert.path("binding").path("declarationVersion").asInt());
        assertEquals("LOT", cert.path("binding").path("matchedPrefix").asText());

        // 子批凭继承的引用完成检验与放行，无需重新绑定
        submitPass(child, "t1", "insp-2");
        approve(child, "qa-2", "QUALITY", 201);
        approve(child, "ops-2", "OPERATIONS", 201);
        assertEquals("RELEASED", currentStatus(child));
        assertEquals(decl, certificate(child).path("releaseSnapshot").path("declarationKey")
                .asText());
    }

    @Test
    void diagnosticsAndQueries_stableOrderingAndNullSemantics() throws Exception {
        String batchKey = "BK-DG-" + unique();
        createBatch(batchKey, "LOT-1", List.of("t1"), 201);
        // 未绑定未放行：null 语义稳定
        JsonNode cert = certificate(batchKey);
        assertTrue(cert.path("binding").isNull());
        assertTrue(cert.path("releaseSnapshot").isNull());

        String declB = "DECL-DG-B-" + unique();
        String declA = "DECL-DG-A-" + unique();
        String declOther = "DECL-DG-O-" + unique();
        // 乱序发布：B 先、A 后；other 物料不匹配
        publish(declB, 1, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(declA, 1, "PROD-1", "LOT", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(declOther, 1, "PROD-9", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                201);
        withdraw(declB, 1, "CK-DG-W", 201);

        // 诊断：按 声明键、版本号 稳定排序；逐条给出匹配评估
        MvcResult diag = mockMvc.perform(get("/api/batches/" + batchKey
                        + "/certificate/diagnostics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode candidates = objectMapper.readTree(diag.getResponse().getContentAsString())
                .path("candidates");
        assertEquals(3, candidates.size());
        assertEquals(declA, candidates.get(0).path("declarationKey").asText());
        assertEquals(declB, candidates.get(1).path("declarationKey").asText());
        assertEquals(declOther, candidates.get(2).path("declarationKey").asText());
        assertTrue(candidates.get(0).path("eligible").asBoolean());
        assertFalse(candidates.get(1).path("eligible").asBoolean());
        assertFalse(candidates.get(1).path("active").asBoolean());
        assertFalse(candidates.get(2).path("materialMatched").asBoolean());

        // 列表按物料过滤且稳定排序
        MvcResult list = mockMvc.perform(get("/api/certificates/declarations")
                        .param("materialCode", "PROD-1"))
                .andExpect(status().isOk()).andReturn();
        JsonNode rows = objectMapper.readTree(list.getResponse().getContentAsString());
        assertEquals(2, rows.size());
        assertEquals(declA, rows.get(0).path("declarationKey").asText());
        assertEquals(declB, rows.get(1).path("declarationKey").asText());

        // 未知声明明细 / 未知批次诊断 → 404
        mockMvc.perform(get("/api/certificates/declarations/NO-SUCH-DECL"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/certificate/diagnostics"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/certificate"))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String publishBody(String commandKey, String declKey, int version, String material,
                               String prefix, int priority, String validFrom, String validTo) {
        return "{\"commandKey\":\"" + commandKey + "\",\"declarationKey\":\"" + declKey
                + "\",\"version\":" + version + ",\"materialCode\":\"" + material
                + "\",\"batchNoPrefix\":\"" + prefix + "\",\"priority\":" + priority
                + ",\"validFrom\":\"" + validFrom + "\",\"validTo\":\"" + validTo + "\"}";
    }

    private MvcResult publish(String commandKey, String declKey, int version, String material,
                              String prefix, int priority, String validFrom, String validTo,
                              int expected) throws Exception {
        return mockMvc.perform(post("/api/certificates/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(commandKey, declKey, version, material, prefix,
                                priority, validFrom, validTo)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult publish(String declKey, int version, String material, String prefix,
                              int priority, String validFrom, String validTo, int expected)
            throws Exception {
        return publish("CK-P-" + unique(), declKey, version, material, prefix, priority,
                validFrom, validTo, expected);
    }

    private void withdraw(String declKey, int version, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/certificates/declarations/" + declKey + "/versions/"
                        + version + "/withdraw")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"reason\":\"撤回原因\"}"))
                .andExpect(status().is(expected));
    }

    private String bindBody(String commandKey, List<String[]> specs) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"bindings\":[");
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"batchKey\":\"").append(specs.get(i)[0])
                    .append("\",\"declarationKey\":\"").append(specs.get(i)[1])
                    .append("\",\"declarationVersion\":").append(specs.get(i)[2]).append('}');
        }
        return sb.append("]}").toString();
    }

    private MvcResult bind(String commandKey, List<String[]> specs, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/certificates/bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bindBody(commandKey, specs)))
                .andExpect(status().is(expected)).andReturn();
    }

    private void createBatch(String batchKey, String batchNo, List<String> items, int expected)
            throws Exception {
        StringBuilder tests = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                tests.append(',');
            }
            tests.append('\"').append(items.get(i)).append('\"');
        }
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-C-" + unique() + "\",\"batchKey\":\""
                                + batchKey + "\",\"productCode\":\"PROD-1\",\"batchNo\":\""
                                + batchNo + "\",\"producedAt\":\"2026-01-02T03:04:05Z\","
                                + "\"requiredTests\":[" + tests + "]}"))
                .andExpect(status().is(expected));
    }

    private void submitPass(String batchKey, String item, String inspector) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                + unique() + "\",\"testItem\":\"" + item
                                + "\",\"result\":\"PASS\",\"inspector\":\"" + inspector + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approve(String batchKey, String actor, String role, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-A-" + unique() + "\"}"))
                .andExpect(status().is(expected));
    }

    private JsonNode certificate(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/certificate"))
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

    private int callStatus(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) {
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

