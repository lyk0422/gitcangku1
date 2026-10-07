package com.example.starter.batch;

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

import java.time.Clock;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批次合格证匹配与供应商声明版本门禁测试：声明发布/撤回、批量绑定回滚、
 * 放行门禁与快照、拆分继承、合批重新校验、幂等与并发裁决、时间边界（可控时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificateFlowTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void setUp() {
        now.set(T0);
        Mockito.when(clock.instant()).thenAnswer(inv -> now.get());
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM certificate_binding");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM declaration");
        jdbc.update("DELETE FROM batch");
    }

    // ---------- 声明发布 ----------

    @Test
    void publishHappy_versionIncrementsAndQueriesStablySorted() throws Exception {
        String declKey = "DECL-P-" + unique();
        publish(declKey, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 同 declarationKey 再次发布 → 版本自增为 2（不同前缀与优先级，避免重叠冲突）
        publish(declKey, "PROD-1", "LOT-X", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        MvcResult list = mockMvc.perform(get("/api/declarations"))
                .andExpect(status().isOk()).andReturn();
        JsonNode all = objectMapper.readTree(list.getResponse().getContentAsString());
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).path("version").asInt());
        assertEquals(2, all.get(1).path("version").asInt());
        assertEquals("ACTIVE", all.get(0).path("status").asText());
        assertTrue(all.get(0).path("withdrawnAt").isNull());

        MvcResult detail = mockMvc.perform(get("/api/declarations/" + declKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertEquals(2, versions.size());
        assertEquals("LOT", versions.get(0).path("batchNoPrefix").asText());
        assertEquals("LOT-X", versions.get(1).path("batchNoPrefix").asText());

        mockMvc.perform(get("/api/declarations/NO-SUCH-DECL"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishInvalidInterval_returns422_andFailureDoesNotOccupyKey() throws Exception {
        String declKey = "DECL-IV-" + unique();
        // validFrom == validTo → 422；validFrom > validTo → 422
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-IV-1", declKey, "PROD-1", "LOT", 1,
                                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-IV-2", declKey, "PROD-1", "LOT", 1,
                                "2027-01-01T00:00:00Z", "2026-01-01T00:00:00Z")))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM declaration",
                Integer.class));

        // 失败不占键：同一 commandKey 修正参数后成功
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-IV-1", declKey, "PROD-1", "LOT", 1,
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void publishOverlappingPrefixSamePriority_returns422_differentPriorityOrMaterialOk()
            throws Exception {
        publish("DECL-OV-A-" + unique(), "PROD-1", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 前缀互为前缀（LOT 与 LOT-2 重叠）且优先级相同 → 422
        publish("DECL-OV-B-" + unique(), "PROD-1", "LOT-2", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 422);
        // 优先级不同 → 允许
        publish("DECL-OV-C-" + unique(), "PROD-1", "LOT-2", 2,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 前缀不重叠（ABC 与 LOT）同优先级 → 允许
        publish("DECL-OV-D-" + unique(), "PROD-1", "ABC", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // 不同物料同前缀同优先级 → 允许
        publish("DECL-OV-E-" + unique(), "PROD-2", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
    }

    @Test
    void publishCommandKey_sameParamsReplays_changedParamsConflicts() throws Exception {
        String declKey = "DECL-IDEM-" + unique();
        String body = publishBody("CK-PIDEM-1", declKey, "PROD-1", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z");
        MvcResult first = mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 重放不新增版本
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM declaration WHERE declaration_key = ?",
                Integer.class, declKey));

        // 同 commandKey 改参 → 409
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-PIDEM-1", declKey, "PROD-1", "LOT-9", 1,
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z")))
                .andExpect(status().isConflict());
    }

    // ---------- 声明撤回 ----------

    @Test
    void withdrawHappy_replayAndDuplicateWithdraw() throws Exception {
        String declKey = "DECL-WD-" + unique();
        publish(declKey, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(declKey, "PROD-1", "LOT-X", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        MvcResult withdrawn = mockMvc.perform(post("/api/declarations/" + declKey + "/withdraw")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-WD-1\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(withdrawn.getResponse().getContentAsString());
        assertEquals(2, body.path("withdrawnVersions").asInt());
        assertEquals("WITHDRAWN", body.path("status").asText());

        // 同 commandKey 重放 → 返回首次快照，不重复撤回
        MvcResult replay = mockMvc.perform(post("/api/declarations/" + declKey + "/withdraw")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-WD-1\"}"))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(withdrawn.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());

        // 新 commandKey 再次撤回 → 409
        withdraw(declKey, "CK-WD-2", 409);
        // 不存在的声明 → 404
        withdraw("NO-SUCH-DECL", "CK-WD-3", 404);

        // 历史版本保留且状态为 WITHDRAWN，撤回时间已固化
        MvcResult detail = mockMvc.perform(get("/api/declarations/" + declKey))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = objectMapper.readTree(detail.getResponse().getContentAsString());
        assertEquals(2, versions.size());
        for (JsonNode v : versions) {
            assertEquals("WITHDRAWN", v.path("status").asText());
            assertFalse(v.path("withdrawnAt").isNull());
        }
    }

    @Test
    void withdrawnPrefixNoLongerBlocksOverlappingPublish() throws Exception {
        String declA = "DECL-WA-" + unique();
        publish(declA, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        withdraw(declA, "CK-WA-1", 201);
        // 撤回后同物料同前缀同优先级不再冲突
        publish("DECL-WB-" + unique(), "PROD-1", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
    }

    // ---------- 批量绑定 ----------

    @Test
    void bindHappy_andListBindingsStablySorted() throws Exception {
        String batchKey = "BK-BIND-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        String decl = "DECL-B-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        MvcResult result = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BH-1\",\"bindings\":[{"
                                + "\"declarationKey\":\"" + decl + "\",\"version\":1}]}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(1, body.path("requestedBindingCount").asInt());
        assertEquals(1, body.path("boundBindingCount").asInt());
        assertEquals(decl, body.path("bindings").get(0).path("declarationKey").asText());
        assertEquals("LOT", body.path("bindings").get(0).path("batchNoPrefix").asText());

        MvcResult list = mockMvc.perform(get("/api/batches/" + batchKey
                        + "/certificate-bindings"))
                .andExpect(status().isOk()).andReturn();
        JsonNode bindings = objectMapper.readTree(list.getResponse().getContentAsString());
        assertEquals(1, bindings.size());
        assertEquals("planner-1", bindings.get(0).path("boundBy").asText());

        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/certificate-bindings"))
                .andExpect(status().isNotFound());
    }

    @Test
    void bindValidationFailures_returnDistinctCodes() throws Exception {
        String batchKey = "BK-BV-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        String decl = "DECL-BV-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        // 批次不存在 → 404
        bind("NO-SUCH-BATCH", decl, 1, 404);
        // 声明版本不存在 → 404
        bind(batchKey, "NO-SUCH-DECL", 1, 404);
        bind(batchKey, decl, 99, 404);
        // 物料不匹配 → 422
        String declOtherMaterial = "DECL-BV-M-" + unique();
        publish(declOtherMaterial, "PROD-2", "LOT", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind(batchKey, declOtherMaterial, 1, 422);
        // 批号前缀不匹配 → 422
        String declPrefix = "DECL-BV-P-" + unique();
        publish(declPrefix, "PROD-1", "XYZ", 1,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind(batchKey, declPrefix, 1, 422);
        // 已过有效期 → 422
        String declExpired = "DECL-BV-E-" + unique();
        publish(declExpired, "PROD-1", "LOT", 9,
                "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z", 201);
        bind(batchKey, declExpired, 1, 422);
        // 已撤回 → 422
        String declWithdrawn = "DECL-BV-W-" + unique();
        publish(declWithdrawn, "PROD-1", "LOT", 8,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        withdraw(declWithdrawn, "CK-BV-W1", 201);
        bind(batchKey, declWithdrawn, 1, 422);

        // 全部失败均不产生绑定
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));

        // 成功绑定后，换 commandKey 重复绑定同一声明版本 → 409
        bind(batchKey, decl, 1, 201);
        bind(batchKey, decl, 1, 409);
    }

    @Test
    void bindOnTerminalStatusBatch_returns409() throws Exception {
        String batchKey = "BK-BS-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        String decl = "DECL-BS-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        // FAIL → REJECTED 后不可绑定
        mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-TF-" + unique() + "\",\"testKey\":\"TK-F\","
                                + "\"testItem\":\"t1\",\"result\":\"FAIL\",\"inspector\":\"i\"}"))
                .andExpect(status().isCreated());
        bind(batchKey, decl, 1, 409);
    }

    @Test
    void bulkBind_anyInvalidRollsBackAll_andFailureDoesNotOccupyKey() throws Exception {
        String batchKey = "BK-BB-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        String declOk = "DECL-BB-OK-" + unique();
        String declBad = "DECL-BB-BAD-" + unique();
        publish(declOk, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(declBad, "PROD-2", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        // 批量绑定含一个物料不匹配的声明 → 整次 422 回滚
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BB-1\",\"bindings\":["
                                + "{\"declarationKey\":\"" + declOk + "\",\"version\":1},"
                                + "{\"declarationKey\":\"" + declBad + "\",\"version\":1}]}"))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));

        // 失败不占键：同一 commandKey 修正参数后成功
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BB-1\",\"bindings\":["
                                + "{\"declarationKey\":\"" + declOk + "\",\"version\":1}]}"))
                .andExpect(status().isCreated());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void bindCommandKey_sameParamsReplays_changedParamsConflicts() throws Exception {
        String batchKey = "BK-BIDEM-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        String decl = "DECL-BI-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        publish(decl, "PROD-1", "LOT-X", 2, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        String body = "{\"commandKey\":\"CK-BI-1\",\"bindings\":["
                + "{\"declarationKey\":\"" + decl + "\",\"version\":1}]}";
        MvcResult first = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/" + batchKey
                        + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));

        // 同 commandKey 改参（换绑定版本）→ 409
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-BI-1\",\"bindings\":["
                                + "{\"declarationKey\":\"" + decl + "\",\"version\":2}]}"))
                .andExpect(status().isConflict());
    }

    // ---------- 放行门禁与快照 ----------

    @Test
    void releaseGate_noValidBinding_returns422_thenBindAndRelease() throws Exception {
        String batchKey = "BK-RG-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        submitAllPass(batchKey, "insp-1");
        approve(batchKey, "qa-1", "QUALITY", "CK-RG-A1", 201);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 未绑定声明：第二批准被门禁拦截 422，批次停留 RELEASE_REVIEW
        approve(batchKey, "ops-1", "OPERATIONS", "CK-RG-A2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));

        // 绑定后放行成功，历史含放行快照
        String decl = "DECL-RG-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind(batchKey, decl, 1, 201);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-RG-A3", 201);
        assertEquals("RELEASED", currentStatus(batchKey));

        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode release = objectMapper.readTree(history.getResponse().getContentAsString())
                .path("release");
        assertEquals(decl, release.path("declarationKey").asText());
        assertEquals(1, release.path("declarationVersion").asInt());
        assertEquals("LOT", release.path("matchedPrefix").asText());
        assertEquals(T0.toString(), release.path("releasedAt").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void releaseGate_withdrawAfterRelease_keepsSnapshot_blocksSubsequentRelease() throws Exception {
        String decl = "DECL-RW-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);

        String released = "BK-RW-1-" + unique();
        createBatch(released, "PROD-1", "LOT-100", List.of("t1"), 201);
        submitAllPass(released, "insp-1");
        bind(released, decl, 1, 201);
        approve(released, "qa-1", "QUALITY", "CK-RW-A1", 201);
        approve(released, "ops-1", "OPERATIONS", "CK-RW-A2", 201);
        assertEquals("RELEASED", currentStatus(released));

        withdraw(decl, "CK-RW-W1", 201);

        // 撤回不改写历史：已放行批次状态与放行快照保持原样
        assertEquals("RELEASED", currentStatus(released));
        MvcResult history = mockMvc.perform(get("/api/batches/" + released + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode release = objectMapper.readTree(history.getResponse().getContentAsString())
                .path("release");
        assertEquals(decl, release.path("declarationKey").asText());
        assertEquals(1, release.path("declarationVersion").asInt());

        // 撤回只影响后续放行：新批次即使已绑定该声明版本也无法放行
        String pending = "BK-RW-2-" + unique();
        createBatch(pending, "PROD-1", "LOT-200", List.of("t1"), 201);
        submitAllPass(pending, "insp-2");
        approve(pending, "qa-2", "QUALITY", "CK-RW-B1", 201);
        approve(pending, "ops-2", "OPERATIONS", "CK-RW-B2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(pending));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?",
                Integer.class, pending));
    }

    @Test
    void releaseGate_validityIntervalBoundaries_controllableClock() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-01T00:00:00Z");
        String decl = "DECL-BND-" + unique();
        publish(decl, "PROD-1", "LOT", 1, from.toString(), to.toString(), 201);

        String batchKey = "BK-BND-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        submitAllPass(batchKey, "insp-1");

        // 区间起点之前：绑定被拒（左闭）
        now.set(from.minusSeconds(1));
        bind(batchKey, decl, 1, 422);
        // 区间起点：绑定成功
        now.set(from);
        bind(batchKey, decl, 1, 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-BND-A1", 201);

        // 区间终点：放行被拒（右开）
        now.set(to);
        approve(batchKey, "ops-1", "OPERATIONS", "CK-BND-A2", 422);
        assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
        // 终点前一秒：放行成功
        now.set(to.minusSeconds(1));
        approve(batchKey, "ops-1", "OPERATIONS", "CK-BND-A3", 201);
        assertEquals("RELEASED", currentStatus(batchKey));
    }

    @Test
    void releaseCheck_reportsActualRequiredAndMissing() throws Exception {
        String batchKey = "BK-RC-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);

        JsonNode none = objectMapper.readTree(mockMvc
                .perform(get("/api/batches/" + batchKey + "/release-check"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, none.path("boundDeclarationCount").asInt());
        assertEquals(0, none.path("validDeclarationCount").asInt());
        assertEquals(1, none.path("requiredValidDeclarationCount").asInt());
        assertEquals(1, none.path("missingValidDeclarationCount").asInt());
        assertFalse(none.path("releasable").asBoolean());

        String decl = "DECL-RC-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        bind(batchKey, decl, 1, 201);

        JsonNode bound = objectMapper.readTree(mockMvc
                .perform(get("/api/batches/" + batchKey + "/release-check"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, bound.path("boundDeclarationCount").asInt());
        assertEquals(1, bound.path("validDeclarationCount").asInt());
        assertEquals(0, bound.path("missingValidDeclarationCount").asInt());
        assertTrue(bound.path("releasable").asBoolean());

        mockMvc.perform(get("/api/batches/NO-SUCH-BATCH/release-check"))
                .andExpect(status().isNotFound());
    }

    // ---------- 拆分继承 ----------

    @Test
    void splitChildren_inheritBindings_andCanReleaseWithoutNewBind() throws Exception {
        String decl = "DECL-SI-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String parent = "BK-SI-P-" + unique();
        createBatch(parent, "PROD-1", "LOT-100", List.of("t1"), 201);
        submitAllPass(parent, "insp-1");
        bind(parent, decl, 1, 201);
        approve(parent, "qa-1", "QUALITY", "CK-SI-A1", 201);
        approve(parent, "ops-1", "OPERATIONS", "CK-SI-A2", 201);

        String child = "BK-SI-C-" + unique();
        mockMvc.perform(post("/api/batches/" + parent + "/split")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-SI-S1\",\"children\":["
                                + "{\"batchKey\":\"" + child + "\",\"batchNo\":\"LOT-100-A\"},"
                                + "{\"batchKey\":\"BK-SI-C2-" + unique()
                                + "\",\"batchNo\":\"LOT-100-B\"}]}"))
                .andExpect(status().isCreated());

        // 子批继承父批绑定引用，保留原绑定操作人
        MvcResult bindings = mockMvc.perform(get("/api/batches/" + child
                        + "/certificate-bindings"))
                .andExpect(status().isOk()).andReturn();
        JsonNode inherited = objectMapper.readTree(bindings.getResponse().getContentAsString());
        assertEquals(1, inherited.size());
        assertEquals(decl, inherited.get(0).path("declarationKey").asText());
        assertEquals(1, inherited.get(0).path("declarationVersion").asInt());
        assertEquals("planner-1", inherited.get(0).path("boundBy").asText());

        // 子批无需重新绑定即可完成放行
        submitAllPass(child, "insp-2");
        approve(child, "qa-2", "QUALITY", "CK-SI-B1", 201);
        approve(child, "ops-2", "OPERATIONS", "CK-SI-B2", 201);
        assertEquals("RELEASED", currentStatus(child));
    }

    // ---------- 合批 ----------

    @Test
    void mergeHappy_coversAllParentMaterials() throws Exception {
        String[] parentA = releaseParent("PROD-A", "LOT-A-1");
        String[] parentB = releaseParent("PROD-B", "LOT-B-1");
        String child = "BK-MG-C-" + unique();

        MvcResult result = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MG-1", child, "M-1", "PROD-AB",
                                List.of(parentA[0], parentB[0]),
                                List.of(parentA[1], parentB[1]))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(child, body.path("childBatchKey").asText());
        assertEquals("QUARANTINED", body.path("childStatus").asText());
        assertEquals(2, body.path("requiredMaterialCount").asInt());
        assertEquals(2, body.path("coveredMaterialCount").asInt());
        assertEquals(0, body.path("missingMaterialCount").asInt());
        assertEquals(2, body.path("parents").size());
        assertEquals("MERGED", body.path("parents").get(0).path("status").asText());

        // 父批 MERGED 不再可用；子批隔离可用
        assertEquals("MERGED", currentStatus(parentA[0]));
        assertEquals("MERGED", currentStatus(parentB[0]));
        List<String> available = availableKeys();
        assertFalse(available.contains(parentA[0]));
        assertFalse(available.contains(parentB[0]));
        assertTrue(available.contains(child));

        // 合批血缘与子批绑定落库
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE child_key = ?", Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, child));
    }

    @Test
    void mergeValidationFailures_rollBack_andFailureDoesNotOccupyKey() throws Exception {
        String[] parentA = releaseParent("PROD-A", "LOT-A-1");
        String[] parentB = releaseParent("PROD-B", "LOT-B-1");
        String child = "BK-MF-C-" + unique();

        // 缺少 PROD-B 物料覆盖 → 422 整次回滚
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-1", child, "M-1", "PROD-AB",
                                List.of(parentA[0], parentB[0]), List.of(parentA[1]))))
                .andExpect(status().isUnprocessableEntity());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM batch_merge",
                Integer.class));
        assertEquals("RELEASED", currentStatus(parentA[0]));
        assertEquals("RELEASED", currentStatus(parentB[0]));

        // 声明物料与所有父批物料均不匹配 → 422
        String declZ = publishOk("PROD-Z", "");
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-2", child, "M-1", "PROD-AB",
                                List.of(parentA[0], parentB[0]),
                                List.of(parentA[1], declZ))))
                .andExpect(status().isUnprocessableEntity());

        // 父批不存在 → 404
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-3", child, "M-1", "PROD-AB",
                                List.of(parentA[0], "NO-SUCH-PARENT"),
                                List.of(parentA[1], parentB[1]))))
                .andExpect(status().isNotFound());

        // 父批键请求内重复 → 409
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-4", child, "M-1", "PROD-AB",
                                List.of(parentA[0], parentA[0]),
                                List.of(parentA[1], parentB[1]))))
                .andExpect(status().isConflict());

        // 非 RELEASED 父批 → 409
        String quarantined = "BK-MF-Q-" + unique();
        createBatch(quarantined, "PROD-A", "LOT-A-9", List.of("t1"), 201);
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-5", child, "M-1", "PROD-AB",
                                List.of(parentA[0], quarantined),
                                List.of(parentA[1], parentB[1]))))
                .andExpect(status().isConflict());

        // 失败不占键：CK-MF-1 修正参数后成功
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-1", child, "M-1", "PROD-AB",
                                List.of(parentA[0], parentB[0]),
                                List.of(parentA[1], parentB[1]))))
                .andExpect(status().isCreated());
        assertEquals("MERGED", currentStatus(parentA[0]));
        assertEquals("MERGED", currentStatus(parentB[0]));

        // 子批键已存在 → 409
        String[] parentC = releaseParent("PROD-A", "LOT-A-2");
        String[] parentD = releaseParent("PROD-B", "LOT-B-2");
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MF-6", child, "M-2", "PROD-AB",
                                List.of(parentC[0], parentD[0]),
                                List.of(parentC[1], parentD[1]))))
                .andExpect(status().isConflict());
    }

    @Test
    void mergeCommandKey_sameParamsReplays_changedParamsConflicts() throws Exception {
        String[] parentA = releaseParent("PROD-A", "LOT-A-1");
        String[] parentB = releaseParent("PROD-B", "LOT-B-1");
        String child = "BK-MI-C-" + unique();
        String body = mergeBody("CK-MI-1", child, "M-1", "PROD-AB",
                List.of(parentA[0], parentB[0]), List.of(parentA[1], parentB[1]));

        MvcResult first = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));

        // 同 commandKey 改参 → 409
        String[] parentC = releaseParent("PROD-A", "LOT-A-3");
        String[] parentD = releaseParent("PROD-B", "LOT-B-3");
        mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mergeBody("CK-MI-1", "BK-MI-C2-" + unique(), "M-2", "PROD-AB",
                                List.of(parentC[0], parentD[0]),
                                List.of(parentC[1], parentD[1]))))
                .andExpect(status().isConflict());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentPublish_sameDeclarationKey_versionsDistinct() throws Exception {
        String declKey = "DECL-CP-" + unique();
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-CP-1", declKey, "PROD-1", "LOT", 1,
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z"))),
                () -> callStatus(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-CP-2", declKey, "PROD-1", "LOT", 1,
                                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z")))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS));
        }
        List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM declaration WHERE declaration_key = ? ORDER BY version",
                Integer.class, declKey);
        assertEquals(List.of(1, 2), versions, "并发发布同一声明键应串行分配不同版本号");
    }

    @Test
    void concurrentBind_sameCommandKey_sameWinnerForBoth() throws Exception {
        String batchKey = "BK-CB-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        String decl = "DECL-CB-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String body = "{\"commandKey\":\"CK-CB-1\",\"bindings\":[{\"declarationKey\":\""
                + decl + "\",\"version\":1}]}";

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate_binding WHERE batch_key = ?",
                Integer.class, batchKey));
    }

    @Test
    void concurrentWithdrawAndFinalApproval_commitOrderDecides() throws Exception {
        String decl = "DECL-CW-" + unique();
        publish(decl, "PROD-1", "LOT", 1, "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        String batchKey = "BK-CW-" + unique();
        createBatch(batchKey, "PROD-1", "LOT-100", List.of("t1"), 201);
        submitAllPass(batchKey, "insp-1");
        bind(batchKey, decl, 1, 201);
        approve(batchKey, "qa-1", "QUALITY", "CK-CW-A1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/declarations/" + decl + "/withdraw")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-W1\"}")),
                () -> callStatus(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", "ops-1").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW-A2\"}"))
        );
        int withdraw = results.get(0).get(30, TimeUnit.SECONDS);
        int approval = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, withdraw, "撤回始终成功");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = ?",
                Integer.class, batchKey);
        if (approval == 201) {
            // 放行先提交：批次 RELEASED，快照固化，随后的撤回不改写历史
            assertEquals("RELEASED", currentStatus(batchKey));
            assertEquals(1, snapshots);
        } else {
            // 撤回先提交：放行读取到已撤回声明，门禁拦截 422
            assertEquals(422, approval);
            assertEquals("RELEASE_REVIEW", currentStatus(batchKey));
            assertEquals(0, snapshots);
        }
    }

    @Test
    void concurrentMerge_sameCommandKey_sameWinnerForBoth() throws Exception {
        String[] parentA = releaseParent("PROD-A", "LOT-A-1");
        String[] parentB = releaseParent("PROD-B", "LOT-B-1");
        String child = "BK-CM-C-" + unique();
        String body = mergeBody("CK-CM-1", child, "M-1", "PROD-AB",
                List.of(parentA[0], parentB[0]), List.of(parentA[1], parentB[1]));

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/merge")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch WHERE batch_key = ?",
                Integer.class, child));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_merge WHERE child_key = ?", Integer.class, child));
        assertEquals("MERGED", currentStatus(parentA[0]));
    }

    // ---------- 合批辅助 ----------

    private int prioritySeq = 100;

    /**
     * 发布一条空前缀声明并返回 declarationKey；优先级递增避免同物料重叠冲突。
     */
    private String publishOk(String material, String prefix) throws Exception {
        String decl = "DECL-AUTO-" + unique();
        publish(decl, material, prefix, prioritySeq++,
                "2020-01-01T00:00:00Z", "2030-01-01T00:00:00Z", 201);
        return decl;
    }

    /**
     * 创建并放行一个父批，返回 {batchKey, declarationKey}；声明前缀为空串，可匹配任意子批批号。
     */
    private String[] releaseParent(String productCode, String batchNo) throws Exception {
        String batchKey = "BK-MP-" + unique();
        createBatch(batchKey, productCode, batchNo, List.of("t1"), 201);
        submitAllPass(batchKey, "insp-p");
        String decl = publishOk(productCode, "");
        bind(batchKey, decl, 1, 201);
        approve(batchKey, "qa-" + unique(), "QUALITY", "CK-MPQ-" + unique(), 201);
        approve(batchKey, "ops-" + unique(), "OPERATIONS", "CK-MPO-" + unique(), 201);
        return new String[]{batchKey, decl};
    }

    private String mergeBody(String commandKey, String childKey, String childBatchNo,
                             String productCode, List<String> parentKeys, List<String> declKeys) {
        StringBuilder sb = new StringBuilder("{\"commandKey\":\"").append(commandKey)
                .append("\",\"childBatchKey\":\"").append(childKey)
                .append("\",\"childBatchNo\":\"").append(childBatchNo)
                .append("\",\"productCode\":\"").append(productCode)
                .append("\",\"requiredTests\":[\"t1\"],\"parentKeys\":[");
        for (int i = 0; i < parentKeys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\"').append(parentKeys.get(i)).append('\"');
        }
        sb.append("],\"bindings\":[");
        for (int i = 0; i < declKeys.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"declarationKey\":\"").append(declKeys.get(i)).append("\",\"version\":1}");
        }
        return sb.append("]}").toString();
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record CreateCmd(String commandKey, String batchKey, String productCode, String batchNo,
                             Instant producedAt, List<String> requiredTests) {
    }

    private void createBatch(String batchKey, String productCode, String batchNo,
                             List<String> items, int expected) throws Exception {
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateCmd("CK-C-" + unique(),
                                batchKey, productCode, batchNo,
                                Instant.parse("2026-01-02T03:04:05Z"), items))))
                .andExpect(status().is(expected));
    }

    private String publishBody(String commandKey, String declKey, String material, String prefix,
                               int priority, String validFrom, String validTo) {
        return "{\"commandKey\":\"" + commandKey + "\",\"declarationKey\":\"" + declKey
                + "\",\"materialCode\":\"" + material + "\",\"batchNoPrefix\":\"" + prefix
                + "\",\"priority\":" + priority + ",\"validFrom\":\"" + validFrom
                + "\",\"validTo\":\"" + validTo + "\"}";
    }

    private void publish(String declKey, String material, String prefix, int priority,
                         String validFrom, String validTo, int expected) throws Exception {
        mockMvc.perform(post("/api/declarations")
                        .header("X-Actor-Id", "supplier-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("CK-P-" + unique(), declKey, material, prefix,
                                priority, validFrom, validTo)))
                .andExpect(status().is(expected));
    }

    private void withdraw(String declKey, String commandKey, int expected) throws Exception {
        mockMvc.perform(post("/api/declarations/" + declKey + "/withdraw")
                        .header("X-Actor-Id", "qa-lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private void bind(String batchKey, String declKey, int version, int expected) throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/certificate-bindings")
                        .header("X-Actor-Id", "planner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-B-" + unique() + "\",\"bindings\":[{"
                                + "\"declarationKey\":\"" + declKey + "\",\"version\":" + version
                                + "}]}"))
                .andExpect(status().is(expected));
    }

    private void submitAllPass(String batchKey, String inspector) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = objectMapper.readTree(history.getResponse().getContentAsString());
        for (JsonNode item : node.path("batch").path("requiredTests")) {
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commandKey\":\"CK-T-" + unique() + "\",\"testKey\":\"TK-"
                                    + unique() + "\",\"testItem\":\"" + item.asText()
                                    + "\",\"result\":\"PASS\",\"inspector\":\"" + inspector
                                    + "\"}"))
                    .andExpect(status().isCreated());
        }
    }

    private void approve(String batchKey, String actor, String role, String commandKey, int expected)
            throws Exception {
        mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected));
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> keys = new ArrayList<>();
        array.forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
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
