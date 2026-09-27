package com.example.starter.evidence;

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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物跨案移交 H2 数据库测试：主流程、422/403/409 失败分支与整批事务回滚、
 * 撤销前置条件、幂等重放/改参/失败不占键、令版本 UTC 左闭右开边界、诊断数量差额与 null 库位语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseTransferApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant BASE = Instant.parse("2026-09-27T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CaseCustodianRepository caseCustodianRepository;
    @Autowired
    private TransferOrderRepository transferOrderRepository;
    @Autowired
    private EvidenceClock evidenceClock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String caseA;
    private String caseB;
    private String caseC;

    @BeforeEach
    void setUp() {
        caseA = "CASE-A-" + uid();
        caseB = "CASE-B-" + uid();
        caseC = "CASE-C-" + uid();
        LocalDateTime wall = LocalDateTime.now();
        caseCustodianRepository.grant(caseA, "alice", wall);
        caseCustodianRepository.grant(caseB, "bob", wall);
        caseCustodianRepository.grant(caseC, "carol", wall);
        LocalDateTime baseUtc = LocalDateTime.ofInstant(BASE, ZoneOffset.UTC);
        transferOrderRepository.register("ORDER-" + caseA, "v1", caseA, caseB,
                baseUtc.minusHours(24), null, wall);
    }

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey, String caseKey, String sealNo,
                             String location) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", sealNo);
        body.put("location", location);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult transfer(String actor, String commandKey, String from, String to,
                               String orderVersion, String fromCust, String toCust,
                               List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("fromCaseKey", from);
        body.put("toCaseKey", to);
        body.put("orderKey", "ORDER-" + from);
        body.put("orderVersion", orderVersion);
        body.put("fromCustodian", fromCust);
        body.put("toCustodian", toCust);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/case-transfers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revoke(String actor, long transferId, String commandKey,
                             String fromCust, String toCust) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("fromCustodian", fromCust);
        body.put("toCustodian", toCust);
        return mockMvc.perform(post("/api/case-transfers/{id}/revoke", transferId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower,
                             LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt);
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult returnLoan(String actor, String evidenceKey, String loanKey,
                                 boolean sealIntact) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", "归还核验");
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "复核");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateHandshake(String actor, String evidenceKey, String to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", to);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult acceptHandshake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode snapshotByKey(JsonNode snapshots, String evidenceKey, String side) {
        for (JsonNode snapshot : snapshots) {
            if (snapshot.get("evidenceKey").asText().equals(evidenceKey)
                    && snapshot.get("side").asText().equals(side)) {
                return snapshot;
            }
        }
        throw new AssertionError("缺少快照: " + evidenceKey + "/" + side);
    }

    private JsonNode diagnosticsByKey(JsonNode itemDiagnostics, String evidenceKey) {
        for (JsonNode item : itemDiagnostics) {
            if (item.get("evidenceKey").asText().equals(evidenceKey)) {
                return item;
            }
        }
        throw new AssertionError("缺少诊断项: " + evidenceKey);
    }

    private JsonNode events(String caseKey, String evidenceKey) throws Exception {
        String url = "/api/case-transfers/cases/" + caseKey + "/events"
                + (evidenceKey == null ? "" : "?evidenceKey=" + evidenceKey);
        MvcResult result = mockMvc.perform(get(url)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private long doSuccessfulTransfer(List<String> keys) throws Exception {
        MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "bob", keys);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.get("evidenceCount").asInt()).isEqualTo(keys.size());
        assertThat(body.get("requestedCount").asInt()).isEqualTo(keys.size());
        return body.get("transferId").asLong();
    }

    @Test
    void transferWritesBothChainsAndSnapshotsAndReassignsEvidence() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA, "SEAL-A-1", "VAULT-A-01");
        intake("alice", ev2, caseA, "SEAL-A-2", null);
        long transfersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM case_transfer", Long.class);

        // 故意乱序提交，服务端规范化排序后落库
        long transferId = doSuccessfulTransfer(List.of(ev2, ev1));
        assertThat(transferId).isPositive();

        MvcResult detail = mockMvc.perform(get("/api/case-transfers/{id}", transferId)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        JsonNode detailBody = json(detail);
        List<String> sortedKeys = List.of(ev1, ev2).stream().sorted().toList();
        assertThat(detailBody.get("evidenceKeys").get(0).asText()).isEqualTo(sortedKeys.get(0));
        assertThat(detailBody.get("evidenceKeys").get(1).asText()).isEqualTo(sortedKeys.get(1));
        assertThat(detailBody.get("orderVersion").asText()).isEqualTo("v1");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM case_transfer", Long.class))
                .isEqualTo(transfersBefore + 1);

        // 每件证物 SOURCE/TARGET 双快照，固化双方案件、原位置（含 null）、封签版本、令版本
        MvcResult snapshots = mockMvc.perform(get("/api/case-transfers/{id}/snapshots", transferId))
                .andReturn();
        assertThat(snapshots.getResponse().getStatus()).isEqualTo(200);
        JsonNode snapshotArray = json(snapshots);
        assertThat(snapshotArray).hasSize(4);
        JsonNode ev1Source = snapshotByKey(snapshotArray, ev1, "SOURCE");
        JsonNode ev1Target = snapshotByKey(snapshotArray, ev1, "TARGET");
        JsonNode ev2Source = snapshotByKey(snapshotArray, ev2, "SOURCE");
        JsonNode ev2Target = snapshotByKey(snapshotArray, ev2, "TARGET");
        assertThat(ev1Source.get("caseKey").asText()).isEqualTo(caseA);
        assertThat(ev1Source.get("location").asText()).isEqualTo("VAULT-A-01");
        assertThat(ev1Source.get("sealNo").asText()).isEqualTo("SEAL-A-1");
        assertThat(ev1Source.get("orderVersion").asText()).isEqualTo("v1");
        assertThat(ev1Target.get("caseKey").asText()).isEqualTo(caseB);
        assertThat(ev2Source.get("location").isNull()).isTrue();
        assertThat(ev2Target.get("location").isNull()).isTrue();

        // 来源案仅保留只读移出链；目标案承接移入链
        JsonNode outEvents = events(caseA, null);
        JsonNode inEvents = events(caseB, null);
        assertThat(outEvents).hasSize(2);
        assertThat(inEvents).hasSize(2);
        assertThat(outEvents.get(0).get("eventType").asText()).isEqualTo("CASE_TRANSFER_OUT");
        assertThat(outEvents.get(0).get("actorId").asText()).isEqualTo("alice");
        assertThat(inEvents.get(0).get("eventType").asText()).isEqualTo("CASE_TRANSFER_IN");
        assertThat(inEvents.get(0).get("actorId").asText()).isEqualTo("bob");

        // 证物当前归属目标案、保管人为目标保管人、仍在库封存
        for (String ev : List.of(ev1, ev2)) {
            JsonNode chain = chain(ev);
            assertThat(chain.get("evidence").get("caseKey").asText()).isEqualTo(caseB);
            assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("bob");
            assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
            // 单件证物保管链跨案件保留 OUT+IN，顺序为 OUT 后 IN
            JsonNode caseEvents = chain.get("caseEvents");
            assertThat(caseEvents).hasSize(2);
            assertThat(caseEvents.get(0).get("eventType").asText()).isEqualTo("CASE_TRANSFER_OUT");
            assertThat(caseEvents.get(0).get("caseKey").asText()).isEqualTo(caseA);
            assertThat(caseEvents.get(1).get("eventType").asText()).isEqualTo("CASE_TRANSFER_IN");
            assertThat(caseEvents.get(1).get("caseKey").asText()).isEqualTo(caseB);
        }

        // 移交后目标案可承接借出，来源案原保管人不再能操作
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        assertThat(borrow("bob", ev1, "dave", LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(borrow("alice", ev2, "dave", LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void sameSourceAndTargetCaseReturns400() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "SEAL-1", "V1");
        MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseA, "v1",
                "alice", "bob", List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(result).get("code").asText()).isEqualTo("SAME_CASE");
    }

    @Test
    void evidenceNotFromSourceCaseBorrowedBrokenSealOrPendingReturns422AndWritesNothing() throws Exception {
        String good = uniqueKey("EV");
        String otherCase = uniqueKey("EV");
        String borrowed = uniqueKey("EV");
        String broken = uniqueKey("EV");
        String pending = uniqueKey("EV");
        intake("alice", good, caseA, "S", "V");
        intake("alice", otherCase, caseC, "S", "V");
        intake("alice", borrowed, caseA, "S", "V");
        intake("alice", broken, caseA, "S", "V");
        intake("alice", pending, caseA, "S", "V");
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        assertThat(borrow("alice", borrowed, "dave",
                LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(inspect("alice", broken, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(initiateHandshake("alice", pending, "erin").getResponse().getStatus()).isEqualTo(200);

        long transfersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM case_transfer", Long.class);
        for (List<String> keys : List.of(
                List.of(good, otherCase),
                List.of(good, borrowed),
                List.of(good, broken),
                List.of(good, pending))) {
            MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                    "alice", "bob", keys);
            assertThat(result.getResponse().getStatus()).isEqualTo(422);
            assertThat(json(result).get("code").asText()).isEqualTo("EVIDENCE_NOT_TRANSFERABLE");
            assertThat(result.getResponse().getContentAsString()).contains("要求数量 2");
            // 整批回滚：不写主单、链事件、快照
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM case_transfer", Long.class))
                    .isEqualTo(transfersBefore);
            assertThat(events(caseA, null)).hasSize(0);
        }
        // 好证物仍在来源案由 alice 保管
        assertThat(chain(good).get("evidence").get("caseKey").asText()).isEqualTo(caseA);
    }

    @Test
    void missingEvidenceReturns422() throws Exception {
        MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "bob", List.of(uniqueKey("MISSING")));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void duplicateEvidenceKeysReturns400() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "bob", List.of(ev, ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(result).get("code").asText()).isEqualTo("DUPLICATE_EVIDENCE");
    }

    @Test
    void orderVersionMissingNotYetEffectiveExpiredAndCaseMismatchReturn403() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        LocalDateTime baseUtc = LocalDateTime.ofInstant(BASE, ZoneOffset.UTC);
        String orderKey = "ORDER-" + caseA;
        transferOrderRepository.register(orderKey, "future", caseA, caseB,
                baseUtc.plusHours(1), null, LocalDateTime.now());
        transferOrderRepository.register(orderKey, "expired", caseA, caseB,
                baseUtc.minusHours(2), baseUtc, LocalDateTime.now());
        transferOrderRepository.register(orderKey, "startsNow", caseA, caseB,
                baseUtc, baseUtc.plusHours(1), LocalDateTime.now());
        transferOrderRepository.register(orderKey, "wrongCases", caseB, caseC,
                baseUtc.minusHours(1), null, LocalDateTime.now());

        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, "nope",
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(403);
        assertThat(json(transfer("alice", uniqueKey("CMD"), caseA, caseB, "future",
                "alice", "bob", List.of(ev))).get("code").asText())
                .isEqualTo("ORDER_VERSION_NOT_EFFECTIVE");
        // 左闭右开：now == effectiveTo 即失效
        assertThat(json(transfer("alice", uniqueKey("CMD"), caseA, caseB, "expired",
                "alice", "bob", List.of(ev))).get("code").asText())
                .isEqualTo("ORDER_VERSION_EXPIRED");
        assertThat(json(transfer("alice", uniqueKey("CMD"), caseA, caseB, "wrongCases",
                "alice", "bob", List.of(ev))).get("code").asText())
                .isEqualTo("ORDER_CASE_MISMATCH");

        // 左闭：now == effectiveFrom 生效，且严格早于 effectiveTo，允许移交
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, "startsNow",
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void sameCustodianOrMissingPermissionReturns403Or409() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        // 双方保管人相同 → 409
        assertThat(json(transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "alice", List.of(ev))).get("code").asText())
                .isEqualTo("CUSTODIANS_SAME");
        // 操作者不是提交的来源保管人 → 409
        assertThat(transfer("bob", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(409);
        // 来源保管人无来源案权限（grace 仅有目标案权限）→ 403
        caseCustodianRepository.grant(caseB, "grace", LocalDateTime.now());
        assertThat(json(transfer("grace", uniqueKey("CMD"), caseA, caseB, "v1",
                "grace", "bob", List.of(ev))).get("code").asText())
                .isEqualTo("NO_FROM_CASE_PERMISSION");
    }

    @Test
    void targetCustodianWithoutTargetCasePermissionReturns403() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        MvcResult result = transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "frank", List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(result).get("code").asText()).isEqualTo("NO_TO_CASE_PERMISSION");
        // 失败后授权补齐，同参数新命令可成功（不残留半成品）
        caseCustodianRepository.grant(caseB, "frank", LocalDateTime.now());
        assertThat(transfer("alice", uniqueKey("CMD"), caseA, caseB, "v1",
                "alice", "frank", List.of(ev)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void revokeAppendsReverseChainAndRestoresEvidenceWithoutDeletingHistory() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "VAULT-1");
        long transferId = doSuccessfulTransfer(List.of(ev));

        MvcResult revoked = revoke("alice", transferId, uniqueKey("CMD"), "alice", "bob");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(revoked);
        assertThat(body.get("status").asText()).isEqualTo("REVOKED");
        assertThat(body.get("revokedByFrom").asText()).isEqualTo("alice");
        assertThat(body.get("revokedByTo").asText()).isEqualTo("bob");
        assertThat(body.get("decidedAt").isNull()).isFalse();

        // 原移交链保留，反向链按顺序追加
        JsonNode chain = chain(ev);
        JsonNode caseEvents = chain.get("caseEvents");
        assertThat(caseEvents).hasSize(4);
        assertThat(caseEvents.get(0).get("eventType").asText()).isEqualTo("CASE_TRANSFER_OUT");
        assertThat(caseEvents.get(1).get("eventType").asText()).isEqualTo("CASE_TRANSFER_IN");
        assertThat(caseEvents.get(2).get("eventType").asText()).isEqualTo("CASE_TRANSFER_REVOKE_OUT");
        assertThat(caseEvents.get(2).get("caseKey").asText()).isEqualTo(caseB);
        assertThat(caseEvents.get(2).get("actorId").asText()).isEqualTo("bob");
        assertThat(caseEvents.get(3).get("eventType").asText()).isEqualTo("CASE_TRANSFER_REVOKE_IN");
        assertThat(caseEvents.get(3).get("caseKey").asText()).isEqualTo(caseA);
        assertThat(caseEvents.get(3).get("actorId").asText()).isEqualTo("alice");
        // 证物回到来源案与来源保管人；双案快照仍为 2 行且未被改写
        assertThat(chain.get("evidence").get("caseKey").asText()).isEqualTo(caseA);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        JsonNode snapshots = json(mockMvc.perform(get("/api/case-transfers/{id}/snapshots", transferId))
                .andReturn());
        assertThat(snapshots).hasSize(2);
        // 目标案链保留了移出与撤销移出两段只读证据
        assertThat(events(caseB, ev)).hasSize(2);
        assertThat(events(caseA, ev)).hasSize(2);
    }

    @Test
    void revokeBlockedAfterTargetLoanInspectionOrHandshake() throws Exception {
        // 后续借出（即使已归还）阻塞撤销
        String evLoan = uniqueKey("EV");
        intake("alice", evLoan, caseA, "S", "V");
        long t1 = doSuccessfulTransfer(List.of(evLoan));
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        MvcResult borrowed = borrow("bob", evLoan, "dave",
                LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(200);
        String loanKey = json(borrowed).get("loanKey").asText();
        MvcResult blocked1 = revoke("bob", t1, uniqueKey("CMD"), "alice", "bob");
        assertThat(blocked1.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(blocked1).get("code").asText())
                .isEqualTo("REVOKE_BLOCKED_BY_SUBSEQUENT_HANDLING");
        assertThat(returnLoan("bob", evLoan, loanKey, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(revoke("bob", t1, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);

        // 后续封签复核（通过，不改状态）也阻塞撤销
        String evInspect = uniqueKey("EV");
        intake("alice", evInspect, caseA, "S", "V");
        long t2 = doSuccessfulTransfer(List.of(evInspect));
        assertThat(inspect("bob", evInspect, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(revoke("alice", t2, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);

        // 后续双人保管链交接（已完成）阻塞撤销
        String evHandshake = uniqueKey("EV");
        intake("alice", evHandshake, caseA, "S", "V");
        long t3 = doSuccessfulTransfer(List.of(evHandshake));
        assertThat(initiateHandshake("bob", evHandshake, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(acceptHandshake("carol", evHandshake).getResponse().getStatus()).isEqualTo(200);
        // 由移交单确认方 bob 发起撤销，仍须因水位之后发生过交接而被拒
        MvcResult blocked3 = revoke("bob", t3, uniqueKey("CMD"), "alice", "bob");
        assertThat(blocked3.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(blocked3).get("code").asText())
                .isEqualTo("REVOKE_BLOCKED_BY_SUBSEQUENT_HANDLING");
        // 全部阻塞场景下原链未被追加反向事件，证物仍在目标案
        assertThat(chain(evLoan).get("evidence").get("caseKey").asText()).isEqualTo(caseB);
        assertThat(chain(evInspect).get("caseEvents")).hasSize(2);
        assertThat(chain(evHandshake).get("caseEvents")).hasSize(2);
    }

    @Test
    void revokeTwiceAndRevokeByOutsiderOrWrongCustodiansReturn409() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        long transferId = doSuccessfulTransfer(List.of(ev));
        assertThat(revoke("bob", transferId, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(200);
        // 新命令再次撤销 → 409
        assertThat(revoke("bob", transferId, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
        // 非双方保管人操作
        String ev2 = uniqueKey("EV");
        intake("alice", ev2, caseA, "S", "V");
        long t2 = doSuccessfulTransfer(List.of(ev2));
        assertThat(revoke("frank", t2, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(revoke("alice", t2, uniqueKey("CMD"), "alice", "carol")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeReplayWithSameCommandKeyReturnsFirstResult() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        long transferId = doSuccessfulTransfer(List.of(ev));
        String commandKey = uniqueKey("CMD");
        MvcResult first = revoke("alice", transferId, commandKey, "alice", "bob");
        MvcResult replay = revoke("alice", transferId, commandKey, "alice", "bob");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复追加反向链
        assertThat(chain(ev).get("caseEvents")).hasSize(4);
    }

    @Test
    void revokeBlockedAfterLaterCrossCaseTransferEvenIfEvidenceReturnsToTargetCase() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        long t1 = doSuccessfulTransfer(List.of(ev));

        // 目标案 B 再把证物移交给 C
        LocalDateTime wall = LocalDateTime.now();
        transferOrderRepository.register("ORDER-" + caseB, "v1", caseB, caseC,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1), null, wall);
        MvcResult second = transfer("bob", uniqueKey("CMD"), caseB, caseC, "v1",
                "bob", "carol", List.of(ev));
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        long t2 = json(second).get("transferId").asLong();

        // 撤销 T2 把证物移回 B 后，T1 目标案 B 已发生过后续跨案移交，T1 仍不可撤销
        assertThat(revoke("carol", t2, uniqueKey("CMD"), "bob", "carol")
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult revokeT1 = revoke("bob", t1, uniqueKey("CMD"), "alice", "bob");
        assertThat(revokeT1.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(revokeT1).get("code").asText())
                .isEqualTo("REVOKE_BLOCKED_BY_SUBSEQUENT_HANDLING");
        // 原 T1 链未被追加反向事件
        assertThat(chain(ev).get("caseEvents")).hasSize(6);
        assertThat(chain(ev).get("evidence").get("caseKey").asText()).isEqualTo(caseB);
    }

    @Test
    void idempotentReplayReturnsSameTransferAndDoesNotDuplicateChains() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA, "S", "V");
        intake("alice", ev2, caseA, "S", "V");
        String commandKey = uniqueKey("CMD");
        List<String> firstOrder = List.of(ev1, ev2);
        MvcResult first = transfer("alice", commandKey, caseA, caseB, "v1",
                "alice", "bob", firstOrder);
        // 仅顺序不同的重放视为同参
        MvcResult replay = transfer("alice", commandKey, caseA, caseB, "v1",
                "alice", "bob", List.of(ev2, ev1));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        long transferId = json(first).get("transferId").asLong();
        assertThat(json(replay).get("transferId").asLong()).isEqualTo(transferId);
        assertThat(chain(ev1).get("caseEvents")).hasSize(2);
        assertThat(chain(ev2).get("caseEvents")).hasSize(2);
        assertThat(events(caseA, null)).hasSize(2);
    }

    @Test
    void sameCommandKeyDifferentParametersReturns409() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA, "S", "V");
        intake("alice", ev2, caseA, "S", "V");
        String commandKey = uniqueKey("CMD");
        assertThat(transfer("alice", commandKey, caseA, caseB, "v1",
                "alice", "bob", List.of(ev1)).getResponse().getStatus()).isEqualTo(200);
        MvcResult changed = transfer("alice", commandKey, caseA, caseB, "v1",
                "alice", "bob", List.of(ev2));
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(changed).get("code").asText()).isEqualTo("IDEMPOTENCY_PARAM_MISMATCH");
    }

    @Test
    void failedAttemptDoesNotOccupyCommandKey() throws Exception {
        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        String ev = uniqueKey("EV");
        intake("alice", ev, caseA, "S", "V");
        MvcResult borrowed = borrow("alice", ev, "dave",
                LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC));
        String loanKey = json(borrowed).get("loanKey").asText();
        String commandKey = uniqueKey("CMD");
        // 借出未归还：422，失败不占键
        assertThat(transfer("alice", commandKey, caseA, caseB, "v1",
                "alice", "bob", List.of(ev)).getResponse().getStatus()).isEqualTo(422);
        // 归还后同一 commandKey、同一参数首次成功
        assertThat(returnLoan("alice", ev, loanKey, true).getResponse().getStatus()).isEqualTo(200);
        MvcResult success = transfer("alice", commandKey, caseA, caseB, "v1",
                "alice", "bob", List.of(ev));
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(ev).get("caseEvents")).hasSize(2);
    }

    @Test
    void diagnosticsReportsActualRequiredAndDifferenceAndReadsDoNotMutate() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseA, "S", "V");
        intake("alice", ev2, caseA, "S", "V");
        long transferId = doSuccessfulTransfer(List.of(ev1, ev2));

        MvcResult ok = mockMvc.perform(get("/api/case-transfers/{id}/diagnostics", transferId))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode diag = json(ok);
        assertThat(diag.get("revocable").asBoolean()).isTrue();
        assertThat(diag.get("requestedCount").asInt()).isEqualTo(2);
        assertThat(diag.get("revocableCount").asInt()).isEqualTo(2);
        assertThat(diag.get("blockedCount").asInt()).isEqualTo(0);

        evidenceClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
        assertThat(borrow("bob", ev1, "dave",
                LocalDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC))
                .getResponse().getStatus()).isEqualTo(200);
        diag = json(mockMvc.perform(get("/api/case-transfers/{id}/diagnostics", transferId))
                .andReturn());
        assertThat(diag.get("revocable").asBoolean()).isFalse();
        assertThat(diag.get("requestedCount").asInt()).isEqualTo(2);
        assertThat(diag.get("revocableCount").asInt()).isEqualTo(1);
        assertThat(diag.get("blockedCount").asInt()).isEqualTo(1);
        JsonNode borrowedItemDiagnostics = diagnosticsByKey(diag.get("itemDiagnostics"), ev1);
        assertThat(borrowedItemDiagnostics.get("revocable").asBoolean()).isFalse();
        assertThat(borrowedItemDiagnostics.get("blockingReasons").toString())
                .contains("后续借出");

        // 只读诊断不改变状态
        assertThat(json(mockMvc.perform(get("/api/case-transfers/{id}", transferId)).andReturn())
                .get("status").asText()).isEqualTo("COMPLETED");
        assertThat(chain(ev1).get("evidence").get("status").asText()).isEqualTo("BORROWED");
    }
}
