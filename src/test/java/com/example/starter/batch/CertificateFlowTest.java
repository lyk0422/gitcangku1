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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 批次合格证匹配端到端测试：声明发布/撤回、批量绑定、放行门禁、拆分继承、
 * 合批重校验、诊断查询、commandKey 幂等与并发裁决。时钟可控，时间一律 UTC。
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
    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> current =
            new AtomicReference<>(Instant.parse("2026-06-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM command_log");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM batch_certificate_binding");
        jdbc.update("DELETE FROM certificate_prefix");
        jdbc.update("DELETE FROM certificate_declaration");
        jdbc.update("DELETE FROM batch_merge");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM recall");
        jdbc.update("DELETE FROM test_result");
        jdbc.update("DELETE FROM batch_required_test");
        jdbc.update("DELETE FROM batch_lineage");
        jdbc.update("DELETE FROM batch");
        current.set(Instant.parse("2026-06-01T00:00:00Z"));
        Mockito.lenient().when(clock.instant()).thenAnswer(inv -> current.get());
        Mockito.lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    // ---------- 声明发布 ----------

    @Test
    void publishHappy_detailAndVersionHistoryStable() throws Exception {
        publish("supplier-1", publishCmd("CK-P1", "DECL-A", 1, "MAT-A",
                prefixes("LOT", 1, "LOTA", 5), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);

        MvcResult detail = mockMvc.perform(get("/api/certificates/DECL-A"))
                .andExpect(status().isOk()).andReturn();
        JsonNode d = read(detail);
        assertEquals("MAT-A", d.path("materialCode").asText());
        assertEquals("ACTIVE", d.path("status").asText());
        assertTrue(d.path("withdrawnBy").isNull());
        assertTrue(d.path("withdrawnAt").isNull());
        // 前缀按优先级降序稳定排序
        assertEquals("LOTA", d.path("prefixes").get(0).path("prefix").asText());
        assertEquals(5, d.path("prefixes").get(0).path("priority").asInt());
        assertEquals("LOT", d.path("prefixes").get(1).path("prefix").asText());

        publish("supplier-1", publishCmd("CK-P2", "DECL-A", 2, "MAT-A",
                prefixes("LOTB", 3), "2026-06-01T00:00:00Z", "2026-12-01T00:00:00Z"), 201);
        MvcResult detail2 = mockMvc.perform(get("/api/certificates/DECL-A"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(2, read(detail2).path("version").asInt(), "明细返回最新版本");

        MvcResult history = mockMvc.perform(get("/api/certificates/DECL-A/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode versions = read(history).path("versions");
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).path("version").asInt());
        assertEquals(2, versions.get(1).path("version").asInt());
        assertEquals("LOTA", versions.get(0).path("prefixes").get(0).path("prefix").asText(),
                "历史版本不被新版本改写");

        mockMvc.perform(get("/api/certificates/NO-SUCH"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/certificates/NO-SUCH/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishInvalidInterval_returns422() throws Exception {
        MvcResult equal = publish("s", publishCmd("CK-I1", "DECL-I", 1, "MAT-I",
                prefixes("A", 1), "2026-06-01T00:00:00Z", "2026-06-01T00:00:00Z"), 422);
        assertEquals("INVALID_INTERVAL", read(equal).path("code").asText());
        MvcResult reversed = publish("s", publishCmd("CK-I2", "DECL-I", 1, "MAT-I",
                prefixes("A", 1), "2026-06-02T00:00:00Z", "2026-06-01T00:00:00Z"), 422);
        assertEquals("INVALID_INTERVAL", read(reversed).path("code").asText());
        assertEquals(0, countRows("certificate_declaration"));
    }

    @Test
    void publishOverlappingPrefixes_samePriority422_differentPriority201() throws Exception {
        MvcResult overlap = publish("s", publishCmd("CK-O1", "DECL-O", 1, "MAT-O",
                prefixes("AB", 1, "ABC", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 422);
        assertEquals("PREFIX_OVERLAP", read(overlap).path("code").asText());
        // 重叠但优先级不同：允许，匹配时高优先级生效
        publish("s", publishCmd("CK-O2", "DECL-O", 1, "MAT-O",
                prefixes("AB", 1, "ABC", 2), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        // 不重叠同优先级：允许
        publish("s", publishCmd("CK-O3", "DECL-O", 2, "MAT-O",
                prefixes("AB", 1, "XY", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
    }

    @Test
    void publishDuplicateVersion_returns409() throws Exception {
        publish("s", publishCmd("CK-D1", "DECL-D", 1, "MAT-D",
                prefixes("A", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        publish("s", publishCmd("CK-D2", "DECL-D", 1, "MAT-D",
                prefixes("B", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 409);
    }
    @Test
    void publishCommandKey_replayConflictAndFailureNotOccupying() throws Exception {
        Map<String, Object> body = publishCmd("CK-EM-1", "DECL-E", 1, "MAT-E",
                prefixes("A", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z");
        MvcResult first = publish("alice", body, 201);
        MvcResult replay = publish("alice", body, 201);
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放返回首次完整快照");
        assertEquals(1, countRows("certificate_declaration"), "重放不新增版本");

        // 同 commandKey 改参 → 409
        publish("alice", publishCmd("CK-EM-1", "DECL-E", 1, "MAT-E2",
                prefixes("A", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 409);
        // 同 commandKey 换操作人 → 409（指纹包含操作者）
        publish("bob", body, 409);

        // 失败不占键：先 422，再同键修正参数成功
        publish("alice", publishCmd("CK-EM-2", "DECL-F", 1, "MAT-F",
                prefixes("A", 1), "2026-06-01T00:00:00Z", "2026-06-01T00:00:00Z"), 422);
        publish("alice", publishCmd("CK-EM-2", "DECL-F", 1, "MAT-F",
                prefixes("A", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
    }

    // ---------- 批量绑定 ----------

    @Test
    void bindHappy_matchedPrefixIsHighestPriority_andIdempotent() throws Exception {
        publish("s", publishCmd("CK-BP", "DECL-B", 1, "MAT-B",
                prefixes("LOT", 1, "LOTB", 5), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-B1", "MAT-B", "LOTB-001", List.of("t1"), 201);

        MvcResult bound = bind("op-1", "CK-BIND-1",
                List.of(List.of("BK-B1", "DECL-B", 1)), 201);
        JsonNode body = read(bound);
        assertEquals(1, body.path("requestedCount").asInt());
        assertEquals(1, body.path("boundCount").asInt());
        assertEquals("LOTB", body.path("bindings").get(0).path("matchedPrefix").asText(),
                "相互重叠的前缀取最高优先级");
        assertEquals("op-1", body.path("bindings").get(0).path("boundBy").asText());

        MvcResult replay = bind("op-1", "CK-BIND-1",
                List.of(List.of("BK-B1", "DECL-B", 1)), 201);
        assertEquals(bound.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertEquals(1, countRows("batch_certificate_binding"));
        // 同键改参 → 409
        bind("op-1", "CK-BIND-1", List.of(List.of("BK-B1", "DECL-B", 1),
                List.of("BK-B1", "DECL-B", 1)), 409);

        MvcResult certs = mockMvc.perform(get("/api/batches/BK-B1/certificates"))
                .andExpect(status().isOk()).andReturn();
        JsonNode c = read(certs);
        assertEquals(1, c.path("bindings").size());
        assertTrue(c.path("releaseSnapshot").isNull(), "未放行时快照为 null");
    }

    @Test
    void bindMaterialMismatch_rollsBackWholeRequest() throws Exception {
        publish("s", publishCmd("CK-X", "DECL-X", 1, "MAT-X",
                prefixes("X", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-X1", "MAT-X", "X-1", List.of("t1"), 201);
        createBatch("BK-X2", "MAT-Y", "Y-1", List.of("t1"), 201);

        MvcResult result = bind("op", "CK-BX", List.of(
                List.of("BK-X1", "DECL-X", 1),
                List.of("BK-X2", "DECL-X", 1)), 422);
        assertEquals("MATERIAL_MISMATCH", read(result).path("code").asText());
        assertEquals(0, countRows("batch_certificate_binding"), "任一失败整次回滚，查询不到半成品");
        MvcResult certs = mockMvc.perform(get("/api/batches/BK-X1/certificates"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, read(certs).path("bindings").size());
    }

    @Test
    void bindWithdrawnOrPrefixMismatch_returns422_andFailureNotOccupyingKey() throws Exception {
        publish("s", publishCmd("CK-Z", "DECL-Z", 1, "MAT-Z",
                prefixes("Z", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        withdraw("s", "DECL-Z", "CK-WZ", 1, 201);
        createBatch("BK-Z1", "MAT-Z", "Z-1", List.of("t1"), 201);
        MvcResult r1 = bind("op", "CK-BZ", List.of(List.of("BK-Z1", "DECL-Z", 1)), 422);
        assertEquals("DECLARATION_WITHDRAWN", read(r1).path("code").asText());
        assertEquals(0, countRows("batch_certificate_binding"));

        // 批号不匹配任何前缀
        publish("s", publishCmd("CK-Z2", "DECL-Z2", 1, "MAT-Z",
                prefixes("Q", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        MvcResult r2 = bind("op", "CK-BZ2", List.of(List.of("BK-Z1", "DECL-Z2", 1)), 422);
        assertEquals("PREFIX_MISMATCH", read(r2).path("code").asText());

        // 404 失败不占键：先绑定不存在批次，再同键绑定真实批次成功
        createBatch("BK-Z2", "MAT-Z", "Q-1", List.of("t1"), 201);
        bind("op", "CK-BZ3", List.of(List.of("BK-NONE", "DECL-Z2", 1)), 404);
        bind("op", "CK-BZ3", List.of(List.of("BK-Z2", "DECL-Z2", 1)), 201);
        assertEquals(1, countRows("batch_certificate_binding"));
    }
    // ---------- 放行门禁与快照 ----------

    @Test
    void releaseGate_requiresValidBinding_thenSnapshotFrozen() throws Exception {
        publish("s", publishCmd("CK-G", "DECL-G", 1, "MAT-G",
                prefixes("LOTG", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-G1", "MAT-G", "LOTG-1", List.of("t1"), 201);
        passAll("BK-G1", List.of("t1"));
        approve("BK-G1", "qa", "QUALITY", "CK-GA1", 201);

        // 物料已发布声明但未绑定：第二批准 422，批次停留 RELEASE_REVIEW
        MvcResult blocked = approveRaw("BK-G1", "ops", "OPERATIONS", "CK-GA2", 422);
        assertEquals("CERTIFICATE_REQUIRED", read(blocked).path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus("BK-G1"));

        bind("op", "CK-GB", List.of(List.of("BK-G1", "DECL-G", 1)), 201);
        approve("BK-G1", "ops", "OPERATIONS", "CK-GA3", 201);
        assertEquals("RELEASED", currentStatus("BK-G1"));

        // 放行快照固化声明版本、匹配前缀与放行时点（可控时钟）
        MvcResult history = mockMvc.perform(get("/api/batches/BK-G1/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode h = read(history);
        JsonNode snapshot = h.path("releaseSnapshot");
        assertEquals("DECL-G", snapshot.path("declarationKey").asText());
        assertEquals(1, snapshot.path("version").asInt());
        assertEquals("LOTG", snapshot.path("matchedPrefix").asText());
        assertEquals("2026-06-01T00:00:00Z", snapshot.path("releasedAt").asText());
        assertEquals(1, h.path("certificateBindings").size());
        assertEquals(0, h.path("mergedFrom").size());
    }

    @Test
    void withdrawAfterRelease_keepsSnapshot_blocksSubsequentRelease() throws Exception {
        publish("s", publishCmd("CK-W", "DECL-W", 1, "MAT-W",
                prefixes("LOTW", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-W1", "MAT-W", "LOTW-1", List.of("t1"), 201);
        bind("op", "CK-WB1", List.of(List.of("BK-W1", "DECL-W", 1)), 201);
        passAll("BK-W1", List.of("t1"));
        approve("BK-W1", "qa", "QUALITY", "CK-WA1", 201);
        approve("BK-W1", "ops", "OPERATIONS", "CK-WA2", 201);
        assertEquals("RELEASED", currentStatus("BK-W1"));

        // 撤回只影响后续放行，不改写历史快照
        MvcResult withdrawn = withdraw("qc", "DECL-W", "CK-WD", 1, 201);
        assertEquals("WITHDRAWN", read(withdrawn).path("status").asText());
        assertEquals("qc", read(withdrawn).path("withdrawnBy").asText());
        MvcResult history = mockMvc.perform(get("/api/batches/BK-W1/history"))
                .andExpect(status().isOk()).andReturn();
        JsonNode snapshot = read(history).path("releaseSnapshot");
        assertEquals("DECL-W", snapshot.path("declarationKey").asText());
        assertEquals("2026-06-01T00:00:00Z", snapshot.path("releasedAt").asText());

        // 重复撤回 409
        withdraw("qc", "DECL-W", "CK-WD2", 1, 409);

        // 后续批次：失效声明不可绑定（422），无有效绑定不得放行（422）
        createBatch("BK-W2", "MAT-W", "LOTW-2", List.of("t1"), 201);
        passAll("BK-W2", List.of("t1"));
        approve("BK-W2", "qa", "QUALITY", "CK-WA3", 201);
        MvcResult bindResult = bind("op", "CK-WB2", List.of(List.of("BK-W2", "DECL-W", 1)), 422);
        assertEquals("DECLARATION_WITHDRAWN", read(bindResult).path("code").asText());
        MvcResult releaseBlocked = approveRaw("BK-W2", "ops", "OPERATIONS", "CK-WA4", 422);
        assertEquals("CERTIFICATE_REQUIRED", read(releaseBlocked).path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus("BK-W2"));
    }

    @Test
    void releaseInterval_leftClosedRightOpen_withControlledClock() throws Exception {
        publish("s", publishCmd("CK-T", "DECL-T", 1, "MAT-T",
                prefixes("LOTT", 1), "2026-06-10T00:00:00Z", "2026-06-20T00:00:00Z"), 201);
        createBatch("BK-T1", "MAT-T", "LOTT-1", List.of("t1"), 201);
        bind("op", "CK-TB1", List.of(List.of("BK-T1", "DECL-T", 1)), 201);
        passAll("BK-T1", List.of("t1"));
        approve("BK-T1", "qa", "QUALITY", "CK-TA1", 201);

        // 区间开始前：绑定失效，422
        current.set(Instant.parse("2026-06-09T23:59:59Z"));
        MvcResult tooEarly = approveRaw("BK-T1", "ops", "OPERATIONS", "CK-TA2", 422);
        assertEquals("BINDING_NOT_VALID", read(tooEarly).path("code").asText());
        // 左闭：validFrom 当刻可放行
        current.set(Instant.parse("2026-06-10T00:00:00Z"));
        approve("BK-T1", "ops", "OPERATIONS", "CK-TA3", 201);
        assertEquals("RELEASED", currentStatus("BK-T1"));

        // 右开：validTo 当刻不可放行
        createBatch("BK-T2", "MAT-T", "LOTT-2", List.of("t1"), 201);
        bind("op", "CK-TB2", List.of(List.of("BK-T2", "DECL-T", 1)), 201);
        passAll("BK-T2", List.of("t1"));
        approve("BK-T2", "qa", "QUALITY", "CK-TA4", 201);
        current.set(Instant.parse("2026-06-20T00:00:00Z"));
        MvcResult expired = approveRaw("BK-T2", "ops", "OPERATIONS", "CK-TA5", 422);
        assertEquals("BINDING_NOT_VALID", read(expired).path("code").asText());
        assertEquals("RELEASE_REVIEW", currentStatus("BK-T2"));
    }
    // ---------- 拆分继承 ----------

    @Test
    void split_childrenInheritBindingReference_prefixRecomputed() throws Exception {
        publish("s", publishCmd("CK-S", "DECL-S", 1, "MAT-S",
                prefixes("LOT", 1, "LOTX", 5), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-SP", "MAT-S", "LOTX-9", List.of("t1"), 201);
        bind("op", "CK-SB", List.of(List.of("BK-SP", "DECL-S", 1)), 201);
        passAll("BK-SP", List.of("t1"));
        approve("BK-SP", "qa", "QUALITY", "CK-SA1", 201);
        approve("BK-SP", "ops", "OPERATIONS", "CK-SA2", 201);

        String splitBody = "{\"commandKey\":\"CK-SPLIT\",\"children\":["
                + "{\"batchKey\":\"BK-SP-C1\",\"batchNo\":\"LOTX-9-A\"},"
                + "{\"batchKey\":\"BK-SP-C2\",\"batchNo\":\"OTHER-1\"}]}";
        mockMvc.perform(post("/api/batches/BK-SP/split")
                        .contentType(MediaType.APPLICATION_JSON).content(splitBody))
                .andExpect(status().isCreated());

        // 子批继承声明引用；批号仍匹配前缀时重算匹配前缀
        MvcResult c1 = mockMvc.perform(get("/api/batches/BK-SP-C1/certificates"))
                .andExpect(status().isOk()).andReturn();
        JsonNode b1 = read(c1).path("bindings").get(0);
        assertEquals("DECL-S", b1.path("declarationKey").asText());
        assertEquals(1, b1.path("version").asInt());
        assertEquals("LOTX", b1.path("matchedPrefix").asText());
        assertEquals("op", b1.path("boundBy").asText(), "继承绑定保留原操作人");

        // 子批批号不匹配任何前缀：引用继承但匹配前缀为 null
        MvcResult c2 = mockMvc.perform(get("/api/batches/BK-SP-C2/certificates"))
                .andExpect(status().isOk()).andReturn();
        JsonNode b2 = read(c2).path("bindings").get(0);
        assertEquals("DECL-S", b2.path("declarationKey").asText());
        assertTrue(b2.path("matchedPrefix").isNull());
        assertEquals("SPLIT", currentStatus("BK-SP"));
    }

    // ---------- 合批 ----------

    @Test
    void mergeHappy_revalidatesAllParentMaterials() throws Exception {
        publish("s", publishCmd("CK-M1", "DECL-M1", 1, "MAT-M1",
                prefixes("MG", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        publish("s", publishCmd("CK-M2", "DECL-M2", 1, "MAT-M2",
                prefixes("MG", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        releaseBatch("BK-MP1", "MAT-M1", "MG-P1", "DECL-M1", List.of("t1"));
        releaseBatch("BK-MP2", "MAT-M2", "MG-P2", "DECL-M2", List.of("t2"));

        MvcResult merged = merge("op", mergeCmd("CK-MG", "BK-MG", "MG-NEW", "MAT-MIX",
                List.of("BK-MP1", "BK-MP2")), 201);
        JsonNode body = read(merged);
        assertEquals("QUARANTINED", body.path("status").asText());
        assertEquals(2, body.path("parentCount").asInt(), "实际父批数");
        assertEquals(2, body.path("requiredParentMin").asInt(), "要求的最小父批数");
        assertEquals(2, body.path("boundDeclarationCount").asInt());
        assertEquals(List.of("t1", "t2"), readStrings(body.path("requiredTests")));

        assertEquals("MERGED", currentStatus("BK-MP1"));
        assertEquals("MERGED", currentStatus("BK-MP2"));
        assertFalse(availableKeys().contains("BK-MP1"));
        assertFalse(availableKeys().contains("BK-MP2"));

        // 新批绑定覆盖全部父物料
        MvcResult certs = mockMvc.perform(get("/api/batches/BK-MG/certificates"))
                .andExpect(status().isOk()).andReturn();
        JsonNode bindings = read(certs).path("bindings");
        assertEquals(2, bindings.size());
        assertEquals("MAT-M1", bindings.get(0).path("materialCode").asText());
        assertEquals("MAT-M2", bindings.get(1).path("materialCode").asText());
        assertEquals("MG", bindings.get(0).path("matchedPrefix").asText());

        MvcResult history = mockMvc.perform(get("/api/batches/BK-MG/history"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("BK-MP1", read(history).path("mergedFrom").get(0).asText());
        assertEquals("BK-MP2", read(history).path("mergedFrom").get(1).asText());

        // 合批幂等：同键同参重放返回首次快照，不新增批次
        MvcResult replay = merge("op", mergeCmd("CK-MG", "BK-MG", "MG-NEW", "MAT-MIX",
                List.of("BK-MP1", "BK-MP2")), 201);
        assertEquals(merged.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键改参 → 409
        merge("op", mergeCmd("CK-MG", "BK-MG", "MG-OTHER", "MAT-MIX",
                List.of("BK-MP1", "BK-MP2")), 409);
    }

    @Test
    void mergeMissingParentMaterialDeclaration_rollsBackWholeMerge() throws Exception {
        publish("s", publishCmd("CK-M4", "DECL-M4", 1, "MAT-M4",
                prefixes("MG", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        // MAT-M3 从未发布声明：父批可按原流程放行
        createBatch("BK-MP3", "MAT-M3", "ANY-1", List.of("t1"), 201);
        passAll("BK-MP3", List.of("t1"));
        approve("BK-MP3", "qa", "QUALITY", "CK-MA1", 201);
        approve("BK-MP3", "ops", "OPERATIONS", "CK-MA2", 201);
        releaseBatch("BK-MP4", "MAT-M4", "MG-P4", "DECL-M4", List.of("t2"));

        MvcResult result = merge("op", mergeCmd("CK-MG2", "BK-MG2", "MG-X", "MAT-MIX",
                List.of("BK-MP3", "BK-MP4")), 422);
        assertEquals("NO_MATCHING_DECLARATION", read(result).path("code").asText());

        // 整次回滚：新批不存在，父批仍 RELEASED，无合批关系与绑定残留
        mockMvc.perform(get("/api/batches/BK-MG2/history"))
                .andExpect(status().isNotFound());
        assertEquals("RELEASED", currentStatus("BK-MP3"));
        assertEquals("RELEASED", currentStatus("BK-MP4"));
        assertEquals(0, countRows("batch_merge"));
        assertEquals(1, countRows("batch_certificate_binding"), "仅父批 BK-MP4 自身的绑定");

        // 父批非 RELEASED 不允许合批
        createBatch("BK-MP5", "MAT-M4", "MG-P5", List.of("t1"), 201);
        merge("op", mergeCmd("CK-MG3", "BK-MG3", "MG-Y", "MAT-MIX",
                List.of("BK-MP4", "BK-MP5")), 409);
        // 父批重复 → 409
        merge("op", mergeCmd("CK-MG4", "BK-MG4", "MG-Z", "MAT-MIX",
                List.of("BK-MP4", "BK-MP4")), 409);
    }
    // ---------- 诊断查询 ----------

    @Test
    void diagnostics_stableOrderingAndDistinctReasons() throws Exception {
        publish("s", publishCmd("CK-DG1", "DECL-D1", 1, "MAT-D",
                prefixes("D", 1), "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z"), 201);
        publish("s", publishCmd("CK-DG2", "DECL-D2", 1, "MAT-D",
                prefixes("D", 1), "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z"), 201);
        withdraw("s", "DECL-D2", "CK-DG2W", 1, 201);
        publish("s", publishCmd("CK-DG3", "DECL-D3", 1, "MAT-D",
                prefixes("E", 1), "2026-01-01T00:00:00Z", "2026-12-31T00:00:00Z"), 201);
        publish("s", publishCmd("CK-DG4", "DECL-D4", 1, "MAT-D",
                prefixes("D", 1), "2027-01-01T00:00:00Z", "2027-12-31T00:00:00Z"), 201);
        publish("s", publishCmd("CK-DG5", "DECL-D5", 1, "MAT-D",
                prefixes("D", 1), "2025-01-01T00:00:00Z", "2025-12-31T00:00:00Z"), 201);

        MvcResult result = mockMvc.perform(get("/api/certificates/diagnostics")
                        .param("materialCode", "MAT-D")
                        .param("batchNo", "D-100")
                        .param("at", "2026-06-01T00:00:00Z"))
                .andExpect(status().isOk()).andReturn();
        JsonNode diag = read(result);
        assertEquals("2026-06-01T00:00:00Z", diag.path("evaluatedAt").asText());
        assertEquals(5, diag.path("totalCount").asInt());
        assertEquals(1, diag.path("eligibleCount").asInt());
        JsonNode candidates = diag.path("candidates");
        String[] expectedKeys = {"DECL-D1", "DECL-D2", "DECL-D3", "DECL-D4", "DECL-D5"};
        String[] expectedReasons = {null, "WITHDRAWN", "PREFIX_MISMATCH", "NOT_YET_VALID", "EXPIRED"};
        for (int i = 0; i < expectedKeys.length; i++) {
            JsonNode c = candidates.get(i);
            assertEquals(expectedKeys[i], c.path("declarationKey").asText(), "稳定排序");
            if (expectedReasons[i] == null) {
                assertTrue(c.path("eligible").asBoolean());
                assertTrue(c.path("reason").isNull(), "可放行时 reason 为 null");
                assertEquals("D", c.path("matchedPrefix").asText());
            } else {
                assertFalse(c.path("eligible").asBoolean());
                assertEquals(expectedReasons[i], c.path("reason").asText());
            }
        }
        // 诊断只读：不改变任何状态
        assertEquals(5, countRows("certificate_declaration"));
        assertEquals(1, countRowsWhere("certificate_declaration",
                "status = 'WITHDRAWN'"));
    }

    // ---------- 并发裁决 ----------

    @Test
    void concurrentWithdrawAndRelease_adjudicatedBySubmissionOrder() throws Exception {
        publish("s", publishCmd("CK-C", "DECL-C", 1, "MAT-C",
                prefixes("LOTC", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-C1", "MAT-C", "LOTC-1", List.of("t1"), 201);
        bind("op", "CK-CB", List.of(List.of("BK-C1", "DECL-C", 1)), 201);
        passAll("BK-C1", List.of("t1"));
        approve("BK-C1", "qa", "QUALITY", "CK-CA1", 201);

        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/BK-C1/approvals")
                        .header("X-Actor-Id", "ops").header("X-Approval-Role", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CA2\"}")),
                () -> callStatus(post("/api/certificates/DECL-C/withdraw")
                        .header("X-Actor-Id", "qc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"CK-CW\",\"version\":1,\"reason\":\"供应商召回声明\"}"))
        );
        int releaseCode = results.get(0).get(30, TimeUnit.SECONDS);
        int withdrawCode = results.get(1).get(30, TimeUnit.SECONDS);
        assertEquals(201, withdrawCode, "撤回按提交顺序生效");

        String declarationStatus = jdbc.queryForObject(
                "SELECT status FROM certificate_declaration WHERE declaration_key = 'DECL-C'",
                String.class);
        assertEquals("WITHDRAWN", declarationStatus);
        String batchStatus = currentStatus("BK-C1");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE batch_key = 'BK-C1'", Integer.class);
        if (releaseCode == 201) {
            // 放行先提交：读到一致的有效声明并固化快照，后续撤回不改写
            assertEquals("RELEASED", batchStatus);
            assertEquals(1, snapshots);
            MvcResult history = mockMvc.perform(get("/api/batches/BK-C1/history"))
                    .andExpect(status().isOk()).andReturn();
            assertEquals("DECL-C", read(history).path("releaseSnapshot").path("declarationKey").asText());
        } else {
            // 撤回先提交：放行读到已撤回声明，422 且无快照
            assertEquals(422, releaseCode);
            assertEquals("RELEASE_REVIEW", batchStatus);
            assertEquals(0, snapshots);
        }
    }

    @Test
    void concurrentSameBindCommandKey_singleWinnerSameSnapshot() throws Exception {
        publish("s", publishCmd("CK-CC", "DECL-CC", 1, "MAT-CC",
                prefixes("C", 1), "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"), 201);
        createBatch("BK-CC1", "MAT-CC", "C-1", List.of("t1"), 201);
        String body = "{\"commandKey\":\"CK-CCB\",\"bindings\":["
                + "{\"batchKey\":\"BK-CC1\",\"declarationKey\":\"DECL-CC\",\"version\":1}]}";
        List<Future<Integer>> results = runConcurrent(
                () -> callStatus(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body)),
                () -> callStatus(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", "op")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
        );
        for (Future<Integer> f : results) {
            assertEquals(201, f.get(30, TimeUnit.SECONDS), "同键同参并发重放均返回首次结果");
        }
        assertEquals(1, countRows("batch_certificate_binding"));
    }

    // ---------- helpers ----------

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private int countRowsWhere(String table, String where) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where,
                Integer.class);
        return count == null ? 0 : count;
    }

    private List<String> readStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
    private List<Map<String, Object>> prefixes(Object... pairs) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("prefix", pairs[i]);
            spec.put("priority", pairs[i + 1]);
            result.add(spec);
        }
        return result;
    }

    private Map<String, Object> publishCmd(String commandKey, String key, int version,
                                           String material, List<Map<String, Object>> prefixes,
                                           String from, String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("declarationKey", key);
        body.put("version", version);
        body.put("materialCode", material);
        body.put("prefixes", prefixes);
        body.put("validFrom", from);
        body.put("validTo", to);
        return body;
    }

    private Map<String, Object> mergeCmd(String commandKey, String batchKey, String batchNo,
                                         String productCode, List<String> parents) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("batchKey", batchKey);
        body.put("batchNo", batchNo);
        body.put("productCode", productCode);
        body.put("parents", parents);
        return body;
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private MvcResult publish(String actor, Map<String, Object> body, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/certificates")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult withdraw(String actor, String declarationKey, String commandKey,
                               int version, int expected) throws Exception {
        return mockMvc.perform(post("/api/certificates/" + declarationKey + "/withdraw")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\",\"version\":" + version
                                + ",\"reason\":\"质量风险\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult bind(String actor, String commandKey, List<List<Object>> items,
                           int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (List<Object> item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("batchKey", item.get(0));
            entry.put("declarationKey", item.get(1));
            entry.put("version", item.get(2));
            bindings.add(entry);
        }
        body.put("bindings", bindings);
        return mockMvc.perform(post("/api/batches/certificate-bindings")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().is(expected)).andReturn();
    }

    private MvcResult merge(String actor, Map<String, Object> body, int expected)
            throws Exception {
        return mockMvc.perform(post("/api/batches/merge")
                        .header("X-Actor-Id", actor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().is(expected)).andReturn();
    }
    private void createBatch(String batchKey, String productCode, String batchNo,
                             List<String> tests, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", "CK-CB-" + unique());
        body.put("batchKey", batchKey);
        body.put("productCode", productCode);
        body.put("batchNo", batchNo);
        body.put("producedAt", "2026-01-02T03:04:05Z");
        body.put("requiredTests", tests);
        mockMvc.perform(post("/api/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().is(expected));
    }


    private void passAll(String batchKey, List<String> items) throws Exception {
        for (String item : items) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commandKey", "CK-T-" + unique());
            body.put("testKey", "TK-" + unique());
            body.put("testItem", item);
            body.put("result", "PASS");
            body.put("inspector", "insp-" + item);
            mockMvc.perform(post("/api/batches/" + batchKey + "/tests")
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isCreated());
        }
    }

    private void approve(String batchKey, String actor, String role, String commandKey,
                         int expected) throws Exception {
        approveRaw(batchKey, actor, role, commandKey, expected);
    }

    private MvcResult approveRaw(String batchKey, String actor, String role, String commandKey,
                                 int expected) throws Exception {
        return mockMvc.perform(post("/api/batches/" + batchKey + "/approvals")
                        .header("X-Actor-Id", actor).header("X-Approval-Role", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandKey\":\"" + commandKey + "\"}"))
                .andExpect(status().is(expected)).andReturn();
    }

    /**
     * 走完一个批次的绑定 + 检验 + 双批准，使其 RELEASED。
     */
    private void releaseBatch(String batchKey, String material, String batchNo,
                              String declarationKey, List<String> tests) throws Exception {
        createBatch(batchKey, material, batchNo, tests, 201);
        bind("op", "CK-RB-" + unique(), List.of(List.of(batchKey, declarationKey, 1)), 201);
        passAll(batchKey, tests);
        approve(batchKey, "qa", "QUALITY", "CK-RA-" + unique(), 201);
        approve(batchKey, "ops", "OPERATIONS", "CK-RB2-" + unique(), 201);
    }

    private String currentStatus(String batchKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/" + batchKey + "/history"))
                .andExpect(status().isOk()).andReturn();
        return read(result).path("batch").path("status").asText();
    }

    private List<String> availableKeys() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/batches/available"))
                .andExpect(status().isOk()).andReturn();
        List<String> keys = new ArrayList<>();
        read(result).forEach(n -> keys.add(n.path("batchKey").asText()));
        return keys;
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