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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物披露包 API 测试：历史快照与截至时刻状态判定、保管链缺口、包版本、
 * 批量增删原子性、冻结不可改写、交付/撤销状态机与幂等边界（固定 UTC 时钟驱动，真实 H2）。
 * 各用例使用唯一案件键，避免共享库内版本号互相影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisclosurePackageApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-22T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @Autowired
    private JdbcTemplate jdbc;

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

    private MvcResult intake(String actor, String evidenceKey, String caseKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult accept(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String loanKey,
                             String borrower, LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
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
        body.put("note", "归还说明");
        return mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createPackage(String actor, String packageKey, String caseKey, int revision,
                                    LocalDateTime asOf, String... evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageKey", packageKey);
        body.put("caseKey", caseKey);
        body.put("revision", revision);
        body.put("asOf", asOf.toString());
        body.put("evidenceKeys", new ArrayList<>(List.of(evidenceKeys)));
        return mockMvc.perform(post("/api/disclosure-packages")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult updateItems(String actor, String packageKey, String commandKey,
                                  List<String> adds, List<String> removes) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("addEvidenceKeys", adds);
        body.put("removeEvidenceKeys", removes);
        return mockMvc.perform(post("/api/disclosure-packages/{key}/items", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult deliver(String actor, String packageKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/disclosure-packages/{key}/deliver", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revoke(String actor, String packageKey, String commandKey,
                             String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/disclosure-packages/{key}/revoke", packageKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult getPackage(String packageKey) throws Exception {
        return mockMvc.perform(get("/api/disclosure-packages/{key}", packageKey)).andReturn();
    }

    private JsonNode packageJson(String packageKey) throws Exception {
        MvcResult result = getPackage(packageKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createFreezesSortedSnapshotsWithReplayedCustodianAndChainVersion() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        // ev1：T+1h 核验通过；ev2：T+2h 发起交接、T+3h 接受，保管人变为 bob
        fixClock(BASE.plusSeconds(3600));
        inspect("alice", ev1, true);
        fixClock(BASE.plusSeconds(7200));
        initiate("alice", ev2, "bob");
        fixClock(BASE.plusSeconds(10800));
        accept("bob", ev2);

        String packageKey = uniqueKey("PKG");
        // 乱序 + 重复键：服务端规范化排序去重
        MvcResult result = createPackage("carol", packageKey, caseKey, 1,
                utc(BASE.plusSeconds(14400)), ev2, ev1, ev2);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode pkg = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(pkg.get("packageKey").asText()).isEqualTo(packageKey);
        assertThat(pkg.get("caseKey").asText()).isEqualTo(caseKey);
        assertThat(pkg.get("revision").asInt()).isEqualTo(1);
        assertThat(pkg.get("status").asText()).isEqualTo("FROZEN");
        assertThat(pkg.get("operatorId").asText()).isEqualTo("carol");
        assertThat(pkg.get("fingerprint").asText()).hasSize(64);
        assertThat(pkg.get("itemCount").asInt()).isEqualTo(2);
        assertThat(pkg.get("summary").asText()).contains(caseKey).contains("v1").contains("2 件");

        JsonNode items = pkg.get("items");
        assertThat(items).hasSize(2);
        // 规范化排序：按证物键字典序
        List<String> sortedKeys = Stream.of(ev1, ev2).sorted().toList();
        assertThat(items.get(0).get("evidenceKey").asText()).isEqualTo(sortedKeys.get(0));
        assertThat(items.get(0).get("seqNo").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("evidenceKey").asText()).isEqualTo(sortedKeys.get(1));
        assertThat(items.get(1).get("seqNo").asInt()).isEqualTo(2);
        Map<String, JsonNode> byKey = new HashMap<>();
        items.forEach(node -> byKey.put(node.get("evidenceKey").asText(), node));
        JsonNode item1 = byKey.get(ev1);
        assertThat(item1.get("custodianId").asText()).isEqualTo("alice");
        assertThat(item1.get("sealIntact").asBoolean()).isTrue();
        assertThat(item1.get("chainVersion").asInt()).isEqualTo(1);
        assertThat(item1.get("chainDigest").asText()).hasSize(64);
        // 截至时刻重放得到交接后的保管人
        JsonNode item2 = byKey.get(ev2);
        assertThat(item2.get("custodianId").asText()).isEqualTo("bob");
        assertThat(item2.get("chainVersion").asInt()).isEqualTo(2);
    }

    @Test
    void createHistoricalSnapshotJudgesByAsOfNotByGenerationTime() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        intake("alice", ev, caseKey);
        // T+1h 借出，T+2h 归还
        fixClock(BASE.plusSeconds(3600));
        borrow("alice", ev, loanKey, "bob", utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(7200));
        returnLoan("alice", ev, loanKey, true);

        // 截至 T+1.5h（借出未归还的历史时刻）：即使生成时已归还，仍 422
        fixClock(BASE.plusSeconds(10800));
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(5400)), ev).getResponse().getStatus()).isEqualTo(422);
        // 截至 T+3h（归还之后）：可生成
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(10800)), ev).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void createWithPendingTransferAtAsOfReturns422EvenIfAcceptedLater() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        fixClock(BASE.plusSeconds(3600));
        initiate("alice", ev, "bob");

        // 截至 T+2h：交接仍待接收（待核验）→ 422
        MvcResult pending = createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(7200)), ev);
        assertThat(pending.getResponse().getStatus()).isEqualTo(422);
        assertThat(pending.getResponse().getContentAsString()).contains("待核验");

        fixClock(BASE.plusSeconds(7200));
        accept("bob", ev);
        // 历史时刻（T+1.5h，介于发起与接受之间）仍是待核验 → 仍 422；截至接受之后 → 201 且保管人为 bob
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(5400)), ev).getResponse().getStatus()).isEqualTo(422);
        MvcResult ok = createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(10800)), ev);
        assertThat(ok.getResponse().getStatus()).isEqualTo(201);
        JsonNode pkg = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(pkg.get("items").get(0).get("custodianId").asText()).isEqualTo("bob");
    }

    @Test
    void createWithBrokenSealAtAsOfReturns422ButEarlierAsOfSucceeds() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        fixClock(BASE.plusSeconds(3600));
        inspect("alice", ev, false);

        MvcResult broken = createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(7200)), ev);
        assertThat(broken.getResponse().getStatus()).isEqualTo(422);
        assertThat(broken.getResponse().getContentAsString()).contains("封签异常");
        // 截至核验之前：封签正常 → 201
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(1800)), ev).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void createWithChainGapAtAsOfReturns422() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        fixClock(BASE.plusSeconds(3600));
        initiate("alice", ev, "bob");
        fixClock(BASE.plusSeconds(7200));
        accept("bob", ev);
        // 直接篡改历史：追加一条保管人不衔接的已接受交接（mallory 而非 bob 交出）
        jdbc.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ev, "mallory", "dave", "ACCEPTED", "mallory",
                utc(BASE.plusSeconds(10800)), utc(BASE.plusSeconds(10800)));

        String packageKey = uniqueKey("PKG");
        MvcResult gap = createPackage("carol", packageKey, caseKey, 1,
                utc(BASE.plusSeconds(14400)), ev);
        assertThat(gap.getResponse().getStatus()).isEqualTo(422);
        assertThat(gap.getResponse().getContentAsString()).contains("缺口");
        // 整包不生成，packageKey 未落库
        assertThat(getPackage(packageKey).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void createWithEvidenceOfOtherCaseOrBeforeIntakeReturns422() throws Exception {
        String caseKey = uniqueKey("CASE");
        String otherCase = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, otherCase);

        MvcResult wrongCase = createPackage("carol", uniqueKey("PKG"), caseKey, 1,
                utc(BASE.plusSeconds(3600)), ev);
        assertThat(wrongCase.getResponse().getStatus()).isEqualTo(422);
        assertThat(wrongCase.getResponse().getContentAsString()).contains("不属于案件");

        MvcResult tooEarly = createPackage("carol", uniqueKey("PKG"), otherCase, 1,
                utc(BASE.minusSeconds(1)), ev);
        assertThat(tooEarly.getResponse().getStatus()).isEqualTo(422);
        assertThat(tooEarly.getResponse().getContentAsString()).contains("尚未入库");
    }

    @Test
    void createWithMissingEvidenceReturns404() throws Exception {
        fixClock(BASE);
        assertThat(createPackage("carol", uniqueKey("PKG"), uniqueKey("CASE"), 1,
                utc(BASE.plusSeconds(3600)), uniqueKey("EV")).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void failedCreateDoesNotOccupyPackageKey() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        String loanKey = uniqueKey("LOAN");
        String packageKey = uniqueKey("PKG");
        intake("alice", ev, caseKey);
        fixClock(BASE.plusSeconds(3600));
        borrow("alice", ev, loanKey, "bob", utc(BASE.plusSeconds(7200)));

        // 截至时刻借出未归还 → 422，失败不占键
        assertThat(createPackage("carol", packageKey, caseKey, 1,
                utc(BASE.plusSeconds(10800)), ev).getResponse().getStatus()).isEqualTo(422);
        fixClock(BASE.plusSeconds(7200));
        returnLoan("alice", ev, loanKey, true);
        // 同键同参重试成功
        MvcResult retry = createPackage("carol", packageKey, caseKey, 1,
                utc(BASE.plusSeconds(10800)), ev);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void samePackageKeyReplaysByNormalizedFingerprint() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        String packageKey = uniqueKey("PKG");
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));

        MvcResult first = createPackage("carol", packageKey, caseKey, 1, asOf, ev2, ev1, ev2);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        // 同键同规范化集合（顺序不同、无重复）：指纹相同 → 重放首次结果
        MvcResult replay = createPackage("carol", packageKey, caseKey, 1, asOf, ev1, ev2);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键改参（不同版本）：指纹不同 → 409
        assertThat(createPackage("carol", packageKey, caseKey, 2, asOf, ev1, ev2)
                .getResponse().getStatus()).isEqualTo(409);
        // 同键不同操作者：指纹不同 → 409
        assertThat(createPackage("dave", packageKey, caseKey, 1, asOf, ev1, ev2)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revisionMustStrictlyExceedExistingForCase() throws Exception {
        String caseKey = uniqueKey("CASE");
        String otherCase = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 2, asOf, ev)
                .getResponse().getStatus()).isEqualTo(201);

        // 相同或更低版本 → 409
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 2, asOf, ev)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 1, asOf, ev)
                .getResponse().getStatus()).isEqualTo(409);
        // 其他案件不受影响：可独立使用相同版本号
        String evOther = uniqueKey("EV");
        intake("alice", evOther, otherCase);
        assertThat(createPackage("carol", uniqueKey("PKG"), otherCase, 2, asOf, evOther)
                .getResponse().getStatus()).isEqualTo(201);
        // 本案件更高版本可建
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 3, asOf, ev)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void batchUpdateValidatesFinalSetAtomically() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        String ev4 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        intake("alice", ev3, caseKey);
        intake("alice", ev4, caseKey);
        // ev4 在截至时刻前被借出且未归还
        fixClock(BASE.plusSeconds(1800));
        borrow("alice", ev4, uniqueKey("LOAN"), "bob", utc(BASE.plusSeconds(7200)));
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));

        String packageKey = uniqueKey("PKG");
        assertThat(createPackage("carol", packageKey, caseKey, 1, asOf, ev1, ev2)
                .getResponse().getStatus()).isEqualTo(201);

        // 合法增删：加 ev3、删 ev1 → 最终集合 {ev2, ev3}（字典序），位次重排
        List<String> expectedAfterUpdate = Stream.of(ev2, ev3).sorted().toList();
        MvcResult updated = updateItems("carol", packageKey, uniqueKey("CMD"),
                List.of(ev3), List.of(ev1));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        JsonNode pkg = objectMapper.readTree(updated.getResponse().getContentAsString());
        assertThat(pkg.get("items").findValuesAsText("evidenceKey"))
                .containsExactlyElementsOf(expectedAfterUpdate);
        assertThat(pkg.get("items").get(0).get("seqNo").asInt()).isEqualTo(1);
        assertThat(pkg.get("items").get(1).get("seqNo").asInt()).isEqualTo(2);
        assertThat(pkg.get("itemCount").asInt()).isEqualTo(2);

        // 新增证物在截至时刻借出未归还 → 422，整批不生效，已有包版本不变
        MvcResult failed = updateItems("carol", packageKey, uniqueKey("CMD"),
                List.of(ev4), List.of(ev2));
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        JsonNode unchanged = packageJson(packageKey);
        assertThat(unchanged.get("items").findValuesAsText("evidenceKey"))
                .containsExactlyElementsOf(expectedAfterUpdate);
        assertThat(unchanged.get("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void batchUpdateFailureBranchesAreDistinguishable() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));
        String packageKey = uniqueKey("PKG");
        assertThat(createPackage("carol", packageKey, caseKey, 1, asOf, ev1, ev2)
                .getResponse().getStatus()).isEqualTo(201);

        // 增删均为空 → 400
        assertThat(updateItems("carol", packageKey, uniqueKey("CMD"), List.of(), List.of())
                .getResponse().getStatus()).isEqualTo(400);
        // 移除不在包内的证物 → 404
        assertThat(updateItems("carol", packageKey, uniqueKey("CMD"), List.of(), List.of("EV-x"))
                .getResponse().getStatus()).isEqualTo(404);
        // 新增已在包内的证物 → 409
        assertThat(updateItems("carol", packageKey, uniqueKey("CMD"), List.of(ev1), List.of())
                .getResponse().getStatus()).isEqualTo(409);
        // 移除全部证物 → 422
        assertThat(updateItems("carol", packageKey, uniqueKey("CMD"), List.of(),
                List.of(ev1, ev2)).getResponse().getStatus()).isEqualTo(422);
        // 包不存在 → 404
        assertThat(updateItems("carol", uniqueKey("PKG"), uniqueKey("CMD"), List.of(ev1),
                List.of()).getResponse().getStatus()).isEqualTo(404);
        // 全部失败后包内容不变
        assertThat(packageJson(packageKey).get("items").findValuesAsText("evidenceKey"))
                .containsExactlyElementsOf(Stream.of(ev1, ev2).sorted().toList());
    }

    @Test
    void laterChainActivityNeverRewritesFrozenPackage() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));
        String packageKey = uniqueKey("PKG");
        MvcResult created = createPackage("carol", packageKey, caseKey, 1, asOf, ev);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String frozenBody = created.getResponse().getContentAsString();

        // 冻结后：复核（核验）、迁移（交接）与新增链记录
        fixClock(BASE.plusSeconds(7200));
        inspect("alice", ev, true);
        fixClock(BASE.plusSeconds(10800));
        initiate("alice", ev, "bob");
        fixClock(BASE.plusSeconds(14400));
        accept("bob", ev);

        // 包内容逐字节不变
        assertThat(getPackage(packageKey).getResponse().getContentAsString())
                .isEqualTo(frozenBody);
        // 逐件链诊断：冻结快照不变，当前链已偏离
        MvcResult diagnosis = mockMvc.perform(get(
                        "/api/disclosure-packages/{pkg}/items/{ev}/diagnosis", packageKey, ev))
                .andReturn();
        assertThat(diagnosis.getResponse().getStatus()).isEqualTo(200);
        JsonNode diag = objectMapper.readTree(diagnosis.getResponse().getContentAsString());
        assertThat(diag.get("frozenCustodianId").asText()).isEqualTo("alice");
        assertThat(diag.get("frozenChainVersion").asInt()).isEqualTo(0);
        assertThat(diag.get("currentCustodianId").asText()).isEqualTo("bob");
        assertThat(diag.get("currentChainVersion").asInt()).isEqualTo(3);
        assertThat(diag.get("changedSinceFreeze").asBoolean()).isTrue();
        assertThat(diag.get("frozenSnapshot").get("custodianId").asText()).isEqualTo("alice");
        // 冻结摘要稳定
        MvcResult summary = mockMvc.perform(get("/api/disclosure-packages/{pkg}/summary",
                packageKey)).andReturn();
        assertThat(summary.getResponse().getStatus()).isEqualTo(200);
        JsonNode sum = objectMapper.readTree(summary.getResponse().getContentAsString());
        assertThat(sum.get("packageDigest").asText()).hasSize(64);
        assertThat(sum.get("items").get(0).get("chainVersion").asInt()).isEqualTo(0);
    }

    @Test
    void deliveredPackageCannotBeRevokedOrModifiedAndNeedsHigherRevision() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));
        String packageKey = uniqueKey("PKG");
        createPackage("carol", packageKey, caseKey, 1, asOf, ev);

        MvcResult delivered = deliver("dave", packageKey, uniqueKey("CMD"));
        assertThat(delivered.getResponse().getStatus()).isEqualTo(200);
        JsonNode pkg = objectMapper.readTree(delivered.getResponse().getContentAsString());
        assertThat(pkg.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(pkg.get("deliveredBy").asText()).isEqualTo("dave");
        assertThat(pkg.get("deliveredAt").isNull()).isFalse();

        // 已交付：不可撤销、不可增删、不可重复交付
        MvcResult revokeDelivered = revoke("carol", packageKey, uniqueKey("CMD"), "发现问题");
        assertThat(revokeDelivered.getResponse().getStatus()).isEqualTo(409);
        assertThat(revokeDelivered.getResponse().getContentAsString()).contains("更高修订包");
        assertThat(updateItems("carol", packageKey, uniqueKey("CMD"), List.of(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(deliver("dave", packageKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);

        // 发现问题须创建更高修订包
        assertThat(createPackage("carol", uniqueKey("PKG"), caseKey, 2, asOf, ev)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void revokeFrozenPackageWritesReasonAndIsTerminal() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));
        String packageKey = uniqueKey("PKG");
        createPackage("carol", packageKey, caseKey, 1, asOf, ev);

        // 空原因 → 400
        assertThat(revoke("carol", packageKey, uniqueKey("CMD"), " ")
                .getResponse().getStatus()).isEqualTo(400);

        fixClock(BASE.plusSeconds(7200));
        MvcResult revoked = revoke("carol", packageKey, uniqueKey("CMD"), "证物清单有误");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode pkg = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(pkg.get("status").asText()).isEqualTo("REVOKED");
        assertThat(pkg.get("revokeReason").asText()).isEqualTo("证物清单有误");
        assertThat(pkg.get("revokedBy").asText()).isEqualTo("carol");

        // 撤销记录可查
        MvcResult record = mockMvc.perform(get("/api/disclosure-packages/{pkg}/revocation",
                packageKey)).andReturn();
        JsonNode revocation = objectMapper.readTree(record.getResponse().getContentAsString());
        assertThat(revocation.get("revoked").asBoolean()).isTrue();
        assertThat(revocation.get("revokeReason").asText()).isEqualTo("证物清单有误");
        assertThat(revocation.get("revokedBy").asText()).isEqualTo("carol");
        assertThat(revocation.get("revokedAt").isNull()).isFalse();

        // 终态：不可再撤销、交付或增删
        assertThat(revoke("carol", packageKey, uniqueKey("CMD"), "再次撤销")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(deliver("carol", packageKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(updateItems("carol", packageKey, uniqueKey("CMD"), List.of(), List.of(ev))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void writeOperationsReplayIdempotentlyAndRejectChangedParams() throws Exception {
        String caseKey = uniqueKey("CASE");
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        LocalDateTime asOf = utc(BASE.plusSeconds(3600));
        String packageKey = uniqueKey("PKG");
        createPackage("carol", packageKey, caseKey, 1, asOf, ev1, ev2);

        // 增删幂等重放
        String updateKey = uniqueKey("CMD");
        MvcResult first = updateItems("carol", packageKey, updateKey, List.of(), List.of(ev2));
        MvcResult replay = updateItems("carol", packageKey, updateKey, List.of(), List.of(ev2));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(packageJson(packageKey).get("items")).hasSize(1);
        // 同键改参 → 409
        assertThat(updateItems("carol", packageKey, updateKey, List.of(), List.of(ev1))
                .getResponse().getStatus()).isEqualTo(409);

        // 交付幂等重放
        String deliverKey = uniqueKey("CMD");
        MvcResult d1 = deliver("carol", packageKey, deliverKey);
        MvcResult d2 = deliver("carol", packageKey, deliverKey);
        assertThat(d1.getResponse().getStatus()).isEqualTo(200);
        assertThat(d2.getResponse().getContentAsString())
                .isEqualTo(d1.getResponse().getContentAsString());
    }

    @Test
    void queriesOnMissingPackageOrItemReturn404() throws Exception {
        String caseKey = uniqueKey("CASE");
        String missing = uniqueKey("PKG");
        assertThat(getPackage(missing).getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{pkg}/summary", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{pkg}/revocation", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{pkg}/items/{ev}/diagnosis",
                missing, "EV-1")).andReturn().getResponse().getStatus()).isEqualTo(404);

        fixClock(BASE);
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String packageKey = uniqueKey("PKG");
        createPackage("carol", packageKey, caseKey, 1, utc(BASE.plusSeconds(3600)), ev);
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{pkg}/items/{ev}/diagnosis",
                packageKey, "EV-other")).andReturn().getResponse().getStatus()).isEqualTo(404);
        // 未撤销包的撤销记录：revoked=false
        MvcResult record = mockMvc.perform(get("/api/disclosure-packages/{pkg}/revocation",
                packageKey)).andReturn();
        JsonNode revocation = objectMapper.readTree(record.getResponse().getContentAsString());
        assertThat(revocation.get("revoked").asBoolean()).isFalse();
        assertThat(revocation.get("revokeReason").isNull()).isTrue();
    }

    @Test
    void createWithMissingFieldsReturns400() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageKey", uniqueKey("PKG"));
        body.put("caseKey", uniqueKey("CASE"));
        // 缺少 revision/asOf/evidenceKeys
        MvcResult result = mockMvc.perform(post("/api/disclosure-packages")
                        .header(ACTOR_HEADER, "carol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}
