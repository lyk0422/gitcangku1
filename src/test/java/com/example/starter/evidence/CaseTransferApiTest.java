package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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
 * 证物跨案移交 API 测试（真实 H2 内存库 MODE=MySQL）：
 * 主流程、规范化排序、422/403/409 失败分支、整批事务回滚、幂等重放与失败不占键、
 * 撤销及后续交接拦截、只读查询不改状态、UTC 左闭右开时间边界。
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
    private JdbcTemplate jdbc;

    @Autowired
    private CaseCustodianGrantRepository grantRepository;

    @Autowired
    private TransferOrderRepository orderRepository;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void grant(String caseKey, String custodian) {
        grantRepository.insert(caseKey, custodian, evidenceClock.nowUtc());
    }

    private void order(String orderNo, int version, String from, String to, OrderStatus status,
                       LocalDateTime validFrom, LocalDateTime validTo) {
        orderRepository.insert(orderNo, version, from, to, status, validFrom, validTo,
                evidenceClock.nowUtc());
    }

    private MvcResult intake(String actor, String evidenceKey, String caseKey, String location)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-" + evidenceKey);
        body.put("location", location);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> transferBody(String commandKey, String orderNo, int version,
                                             String fromCase, String toCase, String fromCust,
                                             String toCust, List<String> evidenceKeys) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("orderNo", orderNo);
        body.put("orderVersion", version);
        body.put("fromCaseKey", fromCase);
        body.put("toCaseKey", toCase);
        body.put("fromCustodian", fromCust);
        body.put("toCustodian", toCust);
        body.put("evidenceKeys", evidenceKeys);
        return body;
    }

    private MvcResult transfer(String actor, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/case-transfers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode transferOk(String actor, Map<String, Object> body) throws Exception {
        MvcResult result = transfer(actor, body);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult revoke(String actor, String transferId, String commandKey,
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

    private JsonNode history(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/case-transfers/by-evidence/{key}/history", evidenceKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 统计关联双案的移交单数（双案键每个测试唯一，避免共享 H2 库中其他测试干扰）。
     */
    private long transferRows(String caseA, String caseB) {
        return count("""
                SELECT COUNT(*) FROM case_transfer
                WHERE (from_case_key = ? AND to_case_key = ?)
                   OR (from_case_key = ? AND to_case_key = ?)
                """, caseA, caseB, caseB, caseA);
    }

    private long chainRowsForCase(String caseKey) {
        return count("SELECT COUNT(*) FROM case_custody_chain_event WHERE case_key = ?", caseKey);
    }

    private long chainRowsForEvidence(String evidenceKey) {
        return count("SELECT COUNT(*) FROM case_custody_chain_event WHERE evidence_key = ?",
                evidenceKey);
    }

    private long snapshotRowsForCase(String caseKey) {
        return count("SELECT COUNT(*) FROM case_seal_snapshot WHERE case_key = ?", caseKey);
    }

    /**
     * 统计某证物已涉及的跨案移交单数（证物键每个测试唯一，避免共享库干扰）。
     */
    private long transferRowsForEvidence(String evidenceKey) {
        return count("""
                SELECT COUNT(*) FROM case_transfer t
                JOIN case_transfer_item i ON i.transfer_id = t.transfer_id
                WHERE i.evidence_key = ?
                """, evidenceKey);
    }

    /**
     * 构造一组双案、双保管人、长期有效移交令与两件在库证物，返回键值载体。
     */
    private Map<String, String> setupTwoEvidenceCase(String ev1, String ev2, String location2) {
        fixClock(BASE);
        String fromCase = uniqueKey("CASE-F");
        String toCase = uniqueKey("CASE-T");
        String orderNo = uniqueKey("ORDER");
        grant(fromCase, "alice");
        grant(toCase, "bob");
        order(orderNo, 1, fromCase, toCase, OrderStatus.VALID,
                LocalDateTime.of(2000, 1, 1, 0, 0), null);
        return Map.of("fromCase", fromCase, "toCase", toCase, "orderNo", orderNo,
                "ev1", ev1, "ev2", ev2, "location2", location2 == null ? "" : location2);
    }

    @Test
    void transferBatchWritesDualChainSnapshotsAndSwitchesCustody() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev1, ev2, "VAULT-A-02"));
        assertThat(intake("alice", ev1, ctx.get("fromCase"), "VAULT-A-01").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(intake("alice", ev2, ctx.get("fromCase"), "VAULT-A-02").getResponse().getStatus())
                .isEqualTo(201);

        JsonNode result = transferOk("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev2, ev1))); // 故意乱序提交

        String transferId = result.get("transferId").asText();
        assertThat(transferId).isNotBlank();
        assertThat(result.get("status").asText()).isEqualTo("TRANSFERRED");
        assertThat(result.get("requestedItemCount").asInt()).isEqualTo(2);
        // 服务端规范化排序：随机键下按实际字典序核对
        List<String> expectedOrder = List.of(ev1, ev2).stream().sorted().toList();
        assertThat(result.get("items").get(0).get("evidenceKey").asText())
                .isEqualTo(expectedOrder.get(0));
        assertThat(result.get("items").get(0).get("seq").asInt()).isZero();
        assertThat(result.get("items").get(1).get("evidenceKey").asText())
                .isEqualTo(expectedOrder.get(1));
        assertThat(result.get("items").get(1).get("seq").asInt()).isEqualTo(1);

        // 每件证物：来源案移出 + 目标案移入两条链，共 4 条
        assertThat(count("SELECT COUNT(*) FROM case_custody_chain_event WHERE transfer_id = ?",
                transferId)).isEqualTo(4);
        // 同一 transferId 双案封存快照：每件两行，共 4 行
        assertThat(count("SELECT COUNT(*) FROM case_seal_snapshot WHERE transfer_id = ?",
                transferId)).isEqualTo(4);

        // 快照固化双方案件、原位置、封签版本与令版本
        JsonNode hist1 = history(ev1);
        assertThat(hist1.get("evidence").get("caseKey").asText()).isEqualTo(ctx.get("toCase"));
        assertThat(hist1.get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(hist1.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(hist1.get("evidence").get("sealVersion").asInt()).isEqualTo(2);
        assertThat(hist1.get("evidence").get("location").asText()).isEqualTo("VAULT-A-01");
        assertThat(hist1.get("snapshots")).hasSize(2);
        JsonNode fromSnapshot = hist1.get("snapshots").get(0);
        JsonNode toSnapshot = hist1.get("snapshots").get(1);
        assertThat(fromSnapshot.get("side").asText()).isEqualTo("FROM");
        assertThat(fromSnapshot.get("caseKey").asText()).isEqualTo(ctx.get("fromCase"));
        assertThat(fromSnapshot.get("originalLocation").asText()).isEqualTo("VAULT-A-01");
        assertThat(fromSnapshot.get("sealVersion").asInt()).isEqualTo(1);
        assertThat(fromSnapshot.get("orderVersion").asInt()).isEqualTo(1);
        assertThat(toSnapshot.get("side").asText()).isEqualTo("TO");
        assertThat(toSnapshot.get("caseKey").asText()).isEqualTo(ctx.get("toCase"));
        // FROM/TO 快照分别关联移出/移入链事件
        assertThat(fromSnapshot.get("chainEventId").asLong())
                .isNotEqualTo(toSnapshot.get("chainEventId").asLong());

        // 事件顺序：CASE_OUT 在前、CASE_IN 在后；来源案保留只读链证据
        assertThat(hist1.get("events").get(0).get("eventType").asText()).isEqualTo("CASE_OUT");
        assertThat(hist1.get("events").get(0).get("caseKey").asText())
                .isEqualTo(ctx.get("fromCase"));
        assertThat(hist1.get("events").get(0).get("custodianId").asText()).isEqualTo("alice");
        assertThat(hist1.get("events").get(1).get("eventType").asText()).isEqualTo("CASE_IN");
        assertThat(hist1.get("events").get(1).get("custodianId").asText()).isEqualTo("bob");

        // 明细查询
        MvcResult detail = mockMvc.perform(get("/api/case-transfers/{id}", transferId)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("transferId").asText()).isEqualTo(transferId);
    }

    @Test
    void nullLocationIsPreservedInSnapshots() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), null);
        intake("alice", ctx.get("ev2"), ctx.get("fromCase"), "VAULT-X");

        JsonNode result = transferOk("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev, ctx.get("ev2"))));
        JsonNode hist = history(ev);
        assertThat(hist.get("evidence").get("location").isNull()).isTrue();
        assertThat(hist.get("snapshots").get(0).get("originalLocation").isNull()).isTrue();
        // 明细按随机证物键排序，按键定位 null 库位项
        JsonNode evItem = result.get("items").get(0);
        for (var item : result.get("items")) {
            if (item.get("evidenceKey").asText().equals(ev)) {
                evItem = item;
            }
        }
        assertThat(evItem.get("originalLocation").isNull()).isTrue();
    }

    @Test
    void sameCaseRejected422AndWritesNothing() throws Exception {
        String ev = uniqueKey("EV");
        String sameCase = uniqueKey("CASE");
        grant(sameCase, "alice");
        grant(sameCase, "bob");
        String orderNo = uniqueKey("ORDER");
        order(orderNo, 1, sameCase, sameCase, OrderStatus.VALID,
                LocalDateTime.of(2000, 1, 1, 0, 0), null);
        intake("alice", ev, sameCase, "V-1");

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"), orderNo, 1,
                sameCase, sameCase, "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(transferRows(sameCase, sameCase)).isZero();
        assertThat(chainRowsForCase(sameCase)).isZero();
        assertThat(snapshotRowsForCase(sameCase)).isZero();
    }

    @Test
    void evidenceNotFromSourceCaseRejected422BatchWritesNothing() throws Exception {
        String good = uniqueKey("EV");
        String foreign = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(good, foreign, null));
        intake("alice", good, ctx.get("fromCase"), "V-1");
        intake("alice", foreign, uniqueKey("CASE-OTHER"), "V-2");

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(good, foreign)));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        // 整批不写入任何保管链，证物归属不变
        assertThat(transferRows(ctx.get("fromCase"), ctx.get("toCase"))).isZero();
        JsonNode goodHist = history(good);
        assertThat(goodHist.get("events")).isEmpty();
        assertThat(goodHist.get("evidence").get("caseKey").asText()).isEqualTo(ctx.get("fromCase"));
        assertThat(goodHist.get("evidence").get("custodianId").asText()).isEqualTo("alice");
    }

    @Test
    void borrowedEvidenceRejected422() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        intake("alice", ctx.get("ev2"), ctx.get("fromCase"), "V-2");
        // ev 借出未归还
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "dave");
        loanBody.put("purpose", "鉴定");
        loanBody.put("dueAt", utc(BASE.plusSeconds(3600)));
        MvcResult loan = mockMvc.perform(post("/api/evidence/{key}/loans", ev)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();
        assertThat(loan.getResponse().getStatus()).isEqualTo(200);

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev, ctx.get("ev2"))));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(transferRows(ctx.get("fromCase"), ctx.get("toCase"))).isZero();
        JsonNode hist = history(ev);
        assertThat(hist.get("events")).isEmpty();
        assertThat(hist.get("evidence").get("status").asText()).isEqualTo("BORROWED");
    }

    @Test
    void brokenSealEvidenceRejected422() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        intake("alice", ctx.get("ev2"), ctx.get("fromCase"), "V-2");
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "封签破损");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", ev)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inspectBody)));

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void personTransferPendingEvidenceRejected422() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        intake("alice", ctx.get("ev2"), ctx.get("fromCase"), "V-2");
        Map<String, Object> personTransfer = new LinkedHashMap<>();
        personTransfer.put("commandKey", uniqueKey("CMD"));
        personTransfer.put("toCustodian", "carol");
        MvcResult initiated = mockMvc.perform(post("/api/evidence/{key}/transfers", ev)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(personTransfer)))
                .andReturn();
        assertThat(initiated.getResponse().getStatus()).isEqualTo(200);

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev, ctx.get("ev2"))));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void evidenceAlreadyInAnotherActiveTransferRejected422() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        transferOk("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));

        // 证物已归属目标案，再以原来源案发起：422（不属于来源案件/已处于其他移交中）
        MvcResult second = transfer("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(second.getResponse().getStatus()).isEqualTo(422);
        assertThat(transferRowsForEvidence(ev)).isEqualTo(1);
    }

    @Test
    void expiredOrderVersionRejected403() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String expiredOrder = uniqueKey("ORDER-EXP");
        // 生效区间 [BASE-2h, BASE)：当前时刻 BASE 恰好等于 validTo，右开失效
        order(expiredOrder, 1, ctx.get("fromCase"), ctx.get("toCase"), OrderStatus.VALID,
                utc(BASE.minusSeconds(7200)), utc(BASE));

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                expiredOrder, 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(transferRowsForEvidence(ev)).isZero();

        // 左闭边界：时刻回退 1 秒到 validTo 之前即生效
        fixClock(BASE.minusSeconds(1));
        MvcResult inside = transfer("alice", transferBody(uniqueKey("CMD"),
                expiredOrder, 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(inside.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void notYetValidOrderVersionRejected403AtLeftBoundary() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String futureOrder = uniqueKey("ORDER-FUT");
        order(futureOrder, 1, ctx.get("fromCase"), ctx.get("toCase"), OrderStatus.VALID,
                utc(BASE.plusSeconds(60)), null);

        // 当前时刻早于 validFrom：403
        MvcResult before = transfer("alice", transferBody(uniqueKey("CMD"),
                futureOrder, 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(before.getResponse().getStatus()).isEqualTo(403);

        // 左闭边界：now == validFrom 生效
        fixClock(BASE.plusSeconds(60));
        MvcResult atBoundary = transfer("alice", transferBody(uniqueKey("CMD"),
                futureOrder, 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(atBoundary.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void revokedOrderVersionRejected403() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String revokedOrder = uniqueKey("ORDER-REV");
        order(revokedOrder, 1, ctx.get("fromCase"), ctx.get("toCase"), OrderStatus.REVOKED,
                LocalDateTime.of(2000, 1, 1, 0, 0), null);

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                revokedOrder, 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void orderCaseMismatchRejected403() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String otherCase = uniqueKey("CASE-OTHER");
        grant(otherCase, "alice");
        String mismatchOrder = uniqueKey("ORDER-MIS");
        order(mismatchOrder, 1, ctx.get("fromCase"), otherCase, OrderStatus.VALID,
                LocalDateTime.of(2000, 1, 1, 0, 0), null);

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                mismatchOrder, 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void missingCaseGrantRejected403() throws Exception {
        String ev = uniqueKey("EV");
        String fromCase = uniqueKey("CASE-F");
        String toCase = uniqueKey("CASE-T");
        String orderNo = uniqueKey("ORDER");
        grant(fromCase, "alice");
        // 故意不授予 bob 对目标案的授权
        order(orderNo, 1, fromCase, toCase, OrderStatus.VALID,
                LocalDateTime.of(2000, 1, 1, 0, 0), null);
        intake("alice", ev, fromCase, "V-1");

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                orderNo, 1, fromCase, toCase, "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void sameCustodianBothSidesRejected409() throws Exception {
        String ev = uniqueKey("EV");
        String fromCase = uniqueKey("CASE-F");
        String toCase = uniqueKey("CASE-T");
        String orderNo = uniqueKey("ORDER");
        grant(fromCase, "alice");
        grant(toCase, "alice");
        order(orderNo, 1, fromCase, toCase, OrderStatus.VALID,
                LocalDateTime.of(2000, 1, 1, 0, 0), null);
        intake("alice", ev, fromCase, "V-1");

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                orderNo, 1, fromCase, toCase, "alice", "alice", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void actorMustBeSourceCustodianOtherwise409() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");

        MvcResult result = transfer("mallory", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(transferRowsForEvidence(ev)).isZero();
    }

    @Test
    void duplicateEvidenceKeyInBatchRejected400() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");

        MvcResult result = transfer("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev, ev)));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void idempotentReplayReturnsFirstSnapshotAndDifferentParamsReturn409() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String commandKey = uniqueKey("CMD");

        MvcResult first = transfer("alice", transferBody(commandKey,
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        MvcResult replay = transfer("alice", transferBody(commandKey,
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 乱序提交同参也视为同一请求
        MvcResult shuffled = transfer("alice", transferBody(commandKey,
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(shuffled.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(transferRowsForEvidence(ev)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM command_log WHERE command_key = ?", commandKey))
                .isEqualTo(1);

        // 同键异参（换令版本）：409
        MvcResult different = transfer("alice", transferBody(commandKey,
                ctx.get("orderNo"), 2, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(different.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedTransferDoesNotOccupyIdempotencyKey() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String commandKey = uniqueKey("CMD");

        // 同保管人导致 409，失败
        MvcResult failed = transfer("alice", transferBody(commandKey,
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "alice", List.of(ev)));
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("SELECT COUNT(*) FROM command_log WHERE command_key = ?", commandKey))
                .isZero();

        // 同键改为合法参数：首次成功，说明失败不占键
        MvcResult success = transfer("alice", transferBody(commandKey,
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        assertThat(success.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("SELECT COUNT(*) FROM command_log WHERE command_key = ?", commandKey))
                .isEqualTo(1);
    }

    @Test
    void revokeAppendsReverseChainWithoutDeletingOriginal() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        JsonNode transfer = transferOk("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));
        String transferId = transfer.get("transferId").asText();

        fixClock(BASE.plusSeconds(30));
        MvcResult revoked = revoke("alice", transferId, uniqueKey("CMD"), "alice", "bob");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("REVOKED");
        assertThat(body.get("revokedAt").isNull()).isFalse();
        assertThat(body.get("revokeFromCustodian").asText()).isEqualTo("alice");
        assertThat(body.get("revokeToCustodian").asText()).isEqualTo("bob");

        JsonNode hist = history(ev);
        // 原 CASE_OUT/CASE_IN 保留，追加 REVERSE_OUT/REVERSE_IN，共 4 条
        assertThat(hist.get("events")).hasSize(4);
        assertThat(hist.get("events").get(0).get("eventType").asText()).isEqualTo("CASE_OUT");
        assertThat(hist.get("events").get(1).get("eventType").asText()).isEqualTo("CASE_IN");
        assertThat(hist.get("events").get(2).get("eventType").asText()).isEqualTo("REVERSE_OUT");
        assertThat(hist.get("events").get(2).get("caseKey").asText()).isEqualTo(ctx.get("toCase"));
        assertThat(hist.get("events").get(3).get("eventType").asText()).isEqualTo("REVERSE_IN");
        assertThat(hist.get("events").get(3).get("caseKey").asText()).isEqualTo(ctx.get("fromCase"));
        // 原双案快照不删除
        assertThat(hist.get("snapshots")).hasSize(2);
        // 证物归属与保管人切回来源案，重新封存封签版本递增
        assertThat(hist.get("evidence").get("caseKey").asText()).isEqualTo(ctx.get("fromCase"));
        assertThat(hist.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(hist.get("evidence").get("sealVersion").asInt()).isEqualTo(3);

        // 重复撤销：409
        MvcResult again = revoke("alice", transferId, uniqueKey("CMD"), "alice", "bob");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeRejectedWhenCustodiansWrongOrSame() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        String transferId = transferOk("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev))).get("transferId").asText();

        assertThat(revoke("alice", transferId, uniqueKey("CMD"), "alice", "carol")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(revoke("alice", transferId, uniqueKey("CMD"), "alice", "alice")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(revoke("mallory", transferId, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
        // 移交仍为 TRANSFERRED
        assertThat(history(ev).get("transfers").get(0).get("status").asText())
                .isEqualTo("TRANSFERRED");
    }

    @Test
    void revokeRejectedAfterTargetCaseLoanAndPersonTransfer() throws Exception {
        String evLoan = uniqueKey("EV");
        Map<String, String> ctxLoan = new LinkedHashMap<>(
                setupTwoEvidenceCase(evLoan, uniqueKey("EV"), null));
        intake("alice", evLoan, ctxLoan.get("fromCase"), "V-1");
        String transferWithLoan = transferOk("alice", transferBody(uniqueKey("CMD"),
                ctxLoan.get("orderNo"), 1, ctxLoan.get("fromCase"), ctxLoan.get("toCase"),
                "alice", "bob", List.of(evLoan))).get("transferId").asText();

        // 目标案承接后发生借出与归还
        fixClock(BASE.plusSeconds(10));
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "dave");
        loanBody.put("purpose", "目标案鉴定");
        loanBody.put("dueAt", utc(BASE.plusSeconds(3600)));
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans", evLoan)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> returnBody = new LinkedHashMap<>();
        returnBody.put("commandKey", uniqueKey("CMD"));
        returnBody.put("loanKey", loanBody.get("loanKey"));
        returnBody.put("sealIntact", true);
        returnBody.put("note", "完好归还");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans/return", evLoan)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(returnBody)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        fixClock(BASE.plusSeconds(20));
        assertThat(revoke("alice", transferWithLoan, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);

        // 另一件证物：目标案承接后发生保管人交接（推进时钟，保证后续交接时刻严格晚于移交）
        fixClock(BASE.plusSeconds(30));
        String evPerson = uniqueKey("EV");
        Map<String, String> ctxPerson = new LinkedHashMap<>(
                setupTwoEvidenceCase(evPerson, uniqueKey("EV"), null));
        intake("alice", evPerson, ctxPerson.get("fromCase"), "V-2");
        String transferWithHandover = transferOk("alice", transferBody(uniqueKey("CMD"),
                ctxPerson.get("orderNo"), 1, ctxPerson.get("fromCase"), ctxPerson.get("toCase"),
                "alice", "bob", List.of(evPerson))).get("transferId").asText();
        // 推进时钟，保证人员交接时刻严格晚于跨案移交
        fixClock(BASE.plusSeconds(40));
        Map<String, Object> personInit = new LinkedHashMap<>();
        personInit.put("commandKey", uniqueKey("CMD"));
        personInit.put("toCustodian", "carol");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers", evPerson)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(personInit)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> acceptBody = new LinkedHashMap<>();
        acceptBody.put("commandKey", uniqueKey("CMD"));
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evPerson)
                        .header(ACTOR_HEADER, "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(acceptBody)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(revoke("alice", transferWithHandover, uniqueKey("CMD"), "alice", "bob")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void targetCustodianCanBorrowAndReturnAfterTransfer() throws Exception {
        String ev = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(ev, uniqueKey("EV"), null));
        intake("alice", ev, ctx.get("fromCase"), "V-1");
        transferOk("alice", transferBody(uniqueKey("CMD"),
                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                "alice", "bob", List.of(ev)));

        // 原来源保管人不再能操作借出
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "dave");
        loanBody.put("purpose", "来源案尝试借出");
        loanBody.put("dueAt", utc(BASE.plusSeconds(3600)));
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans", ev)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        // 目标案保管人 bob 承接借出与归还
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("purpose", "目标案鉴定");
        MvcResult borrowed = mockMvc.perform(post("/api/evidence/{key}/loans", ev)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();
        assertThat(borrowed.getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> returnBody = new LinkedHashMap<>();
        returnBody.put("commandKey", uniqueKey("CMD"));
        returnBody.put("loanKey", loanBody.get("loanKey"));
        returnBody.put("sealIntact", true);
        returnBody.put("note", "完好归还");
        MvcResult returned = mockMvc.perform(post("/api/evidence/{key}/loans/return", ev)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(returnBody)))
                .andReturn();
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);
        JsonNode hist = history(ev);
        assertThat(hist.get("evidence").get("caseKey").asText()).isEqualTo(ctx.get("toCase"));
        assertThat(hist.get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(hist.get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void diagnoseIsReadOnlyAndReportsActualRequiredAndDeltaValues() throws Exception {
        String good = uniqueKey("EV");
        String broken = uniqueKey("EV");
        Map<String, String> ctx = new LinkedHashMap<>(setupTwoEvidenceCase(good, broken, null));
        intake("alice", good, ctx.get("fromCase"), "V-1");
        intake("alice", broken, ctx.get("fromCase"), "V-9");
        Map<String, Object> inspectBody = new LinkedHashMap<>();
        inspectBody.put("commandKey", uniqueKey("CMD"));
        inspectBody.put("passed", false);
        inspectBody.put("note", "破损");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", broken)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inspectBody)));

        long transfersBefore = transferRowsForEvidence(good) + transferRowsForEvidence(broken);
        long chainsBefore = chainRowsForEvidence(good) + chainRowsForEvidence(broken);
        MvcResult result = mockMvc.perform(post("/api/case-transfers/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferBody(uniqueKey("CMD"),
                                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                                "alice", "bob", List.of(broken, good)))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode diagnosis = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(diagnosis.get("now").asText()).startsWith("2026-09-27T08:00:00");
        assertThat(diagnosis.get("requestedItemCount").asInt()).isEqualTo(2);
        assertThat(diagnosis.get("existingItemCount").asInt()).isEqualTo(2);
        assertThat(diagnosis.get("blockedItemCount").asInt()).isEqualTo(1);
        assertThat(diagnosis.get("custodiansDistinct").asBoolean()).isTrue();
        assertThat(diagnosis.get("order").get("effective").asBoolean()).isTrue();
        assertThat(diagnosis.get("transferable").asBoolean()).isFalse();
        // 明细规范化排序：按随机键定位 good/broken，不再假定固定下标
        JsonNode goodItem = null;
        JsonNode brokenItem = null;
        for (var item : diagnosis.get("items")) {
            if (item.get("evidenceKey").asText().equals(good)) {
                goodItem = item;
            } else if (item.get("evidenceKey").asText().equals(broken)) {
                brokenItem = item;
            }
        }
        assertThat(goodItem).isNotNull();
        assertThat(brokenItem).isNotNull();
        assertThat(goodItem.get("transferable").asBoolean()).isTrue();
        assertThat(brokenItem.get("transferable").asBoolean()).isFalse();
        assertThat(brokenItem.get("reason").asText()).contains("封签异常");

        // 只读：不写入任何数据；重复诊断结果一致
        MvcResult second = mockMvc.perform(post("/api/case-transfers/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferBody(uniqueKey("CMD"),
                                ctx.get("orderNo"), 1, ctx.get("fromCase"), ctx.get("toCase"),
                                "alice", "bob", List.of(broken, good)))))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(transferRowsForEvidence(good) + transferRowsForEvidence(broken))
                .isEqualTo(transfersBefore);
        assertThat(chainRowsForEvidence(good) + chainRowsForEvidence(broken))
                .isEqualTo(chainsBefore);
    }
}
