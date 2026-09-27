package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * 披露包主流程、历史快照与失败分支测试：所有判定按截至 UTC 时刻的历史状态，
 * 成功包冻结链版本/位置/封签快照，之后状态变化不得改写包；失败不留半成品。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisclosurePackageApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String cutoffSoon() {
        return LocalDateTime.now(ZoneOffset.UTC).plusSeconds(2).toString();
    }

    private Map<String, Object> intakeBody(String commandKey, String evidenceKey, String caseKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return body;
    }

    private void intake(String actor, String evidenceKey, String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                intakeBody(uniqueKey("CMD"), evidenceKey, caseKey))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void transfer(String from, String to, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", to);
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, from)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> accept = new LinkedHashMap<>();
        accept.put("commandKey", uniqueKey("CMD"));
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
                        .header(ACTOR_HEADER, to)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accept)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private void inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private void borrow(String custodian, String borrower, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "court review");
        body.put("dueAt", clock.nowUtc().plusHours(24).toString());
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private void returnLoan(String custodian, String borrower, String evidenceKey, boolean sealIntact)
            throws Exception {
        // 先查出本次未归还借出的 loanKey
        MvcResult loansResult = mockMvc.perform(
                        get("/api/evidence/loans/by-borrower/{borrowerId}", borrower))
                .andReturn();
        JsonNode loans = objectMapper.readTree(loansResult.getResponse().getContentAsString());
        String loanKey = null;
        for (JsonNode loan : loans) {
            if (evidenceKey.equals(loan.get("evidenceKey").asText()) && "ACTIVE".equals(loan.get("status").asText())) {
                loanKey = loan.get("loanKey").asText();
            }
        }
        assertThat(loanKey).as("应存在未归还借出").isNotNull();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("sealIntact", sealIntact);
        body.put("note", "returned intact");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans/return", evidenceKey)
                        .header(ACTOR_HEADER, custodian)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult createPackage(String actor, String commandKey, String caseKey,
                                    List<String> evidenceKeys, String cutoff) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("caseKey", caseKey);
        // 故意打乱顺序，验证服务端规范化排序
        body.put("evidenceKeys", evidenceKeys.reversed());
        body.put("cutoffAt", cutoff);
        return mockMvc.perform(post("/api/disclosure-packages")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult commandPost(String path, String actor, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post(path)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode getPackage(String packageKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/disclosure-packages/{key}", packageKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createPackageFreezesCanonicallySortedItemsAtCutoff() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        inspect("alice", ev1, true);
        transfer("alice", "bob", ev2);

        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev1, ev2), cutoffSoon());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode detail = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode pkg = detail.get("pkg");
        assertThat(pkg.get("status").asText()).isEqualTo("FROZEN");
        assertThat(pkg.get("packageVersion").asInt()).isEqualTo(1);
        assertThat(pkg.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(pkg.get("packageDigest").asText()).hasSize(64);
        assertThat(pkg.get("basePackageKey").isNull()).isTrue();

        JsonNode items = detail.get("items");
        assertThat(items).hasSize(2);
        // 字典序规范化，与请求顺序无关
        assertThat(items.get(0).get("evidenceKey").asText()).isLessThan(items.get(1).get("evidenceKey").asText());
        JsonNode first = items.get(0).get("evidenceKey").asText().equals(ev1) ? items.get(0) : items.get(1);
        JsonNode second = items.get(0).get("evidenceKey").asText().equals(ev2) ? items.get(0) : items.get(1);
        assertThat(first.get("evidenceKey").asText()).isEqualTo(ev1);
        assertThat(second.get("evidenceKey").asText()).isEqualTo(ev2);
        assertThat(first.get("custodianId").asText()).isEqualTo("alice");
        assertThat(first.get("sealOk").asBoolean()).isTrue();
        assertThat(first.get("chainVersion").asInt()).isEqualTo(2);
        // ev2 完成过一次交接：截至位置为 bob
        assertThat(second.get("custodianId").asText()).isEqualTo("bob");
        assertThat(second.get("statusAtCutoff").asText()).isEqualTo("SEALED");
        assertThat(second.get("chainVersion").asInt()).isEqualTo(3);
        assertThat(second.get("itemDigest").asText()).hasSize(64);
    }

    @Test
    void cutoffJudgesHistoryNotGenerationTimeForPendingTransfer() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1).toString();

        // 等待超过截至时刻后再发起交接：生成时处于待核验，但截至时刻尚未发生
        Thread.sleep(1600);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", "bob");
        mockMvc.perform(post("/api/evidence/{key}/transfers", ev)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();

        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoff);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items").get(0);
        assertThat(item.get("statusAtCutoff").asText()).isEqualTo("SEALED");
        assertThat(item.get("custodianId").asText()).isEqualTo("alice");
    }

    @Test
    void pendingTransferAtCutoffReturns422EvenIfAcceptedLater() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", "bob");
        mockMvc.perform(post("/api/evidence/{key}/transfers", ev)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
        String cutoff = cutoffSoon();

        // 截至时刻后才接受交接：生成时已 SEALED，但截至时刻为待核验，仍必须 422
        Thread.sleep(2200);
        Map<String, Object> accept = new LinkedHashMap<>();
        accept.put("commandKey", uniqueKey("CMD"));
        mockMvc.perform(post("/api/evidence/{key}/transfers/accept", ev)
                .header(ACTOR_HEADER, "bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(accept))).andReturn();

        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoff);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("待核验");
    }

    @Test
    void borrowedUnreturnedAtCutoffReturns422EvenIfReturnedLater() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        borrow("alice", "bob", ev);
        String cutoff = cutoffSoon();

        Thread.sleep(2200);
        returnLoan("alice", "bob", ev, true);

        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoff);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("借出未归还");
    }

    @Test
    void returnedBeforeCutoffIsAcceptedAndLoanHistoryFrozen() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        borrow("alice", "bob", ev);
        returnLoan("alice", "bob", ev, true);

        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoffSoon());
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items.get(0).get("statusAtCutoff").asText()).isEqualTo("SEALED");
        // 归还时同事务追加一条封签核验记录，链事件含核验
        List<String> kinds = items.get(0).get("events").findValuesAsText("kind");
        assertThat(kinds).containsExactly("INTAKE", "LOAN_BORROW", "LOAN_RETURN", "INSPECTION");
    }

    @Test
    void brokenSealAfterCutoffDoesNotBlockPackageAndFrozenSnapshotKeepsIntactSeal() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1).toString();

        // 截至时刻之后复核失败：生成时封签已异常，但截至时刻封签正常
        Thread.sleep(1600);
        inspect("alice", ev, false);

        MvcResult created = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoff);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode detail = objectMapper.readTree(created.getResponse().getContentAsString());
        JsonNode item = detail.get("items").get(0);
        assertThat(item.get("sealOk").asBoolean()).isTrue();
        assertThat(item.get("statusAtCutoff").asText()).isEqualTo("SEALED");
        assertThat(item.get("events").findValuesAsText("kind")).containsExactly("INTAKE");
    }

    @Test
    void frozenPackageIsNotChangedByLaterMigrationInspectionOrLoan() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String cutoff = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1).toString();
        MvcResult created = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoff);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode before = objectMapper.readTree(created.getResponse().getContentAsString());
        String packageKey = before.get("pkg").get("packageKey").asText();
        String beforeDigest = before.get("pkg").get("packageDigest").asText();
        int beforeVersion = before.get("items").get(0).get("chainVersion").asInt();

        // 截至时刻之后发生迁移、复核、借出归还：当前状态变化，但冻结包不得改变
        Thread.sleep(1600);
        transfer("alice", "bob", ev);
        inspect("bob", ev, true);
        borrow("bob", "carol", ev);
        returnLoan("bob", "carol", ev, true);

        JsonNode after = getPackage(packageKey);
        assertThat(after.get("pkg").get("packageDigest").asText()).isEqualTo(beforeDigest);
        assertThat(after.get("items")).hasSize(1);
        assertThat(after.get("items").get(0).get("custodianId").asText()).isEqualTo("alice");
        assertThat(after.get("items").get(0).get("chainVersion").asInt()).isEqualTo(beforeVersion);
        assertThat(after.get("items").get(0).get("statusAtCutoff").asText()).isEqualTo("SEALED");
    }

    @Test
    void evidenceFromOtherCaseOrMissingReturns422WithoutCreatingPackage() throws Exception {
        String caseKey = uniqueKey("CASE");
        String otherCase = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        String alien = uniqueKey("EV");
        intake("alice", ev, caseKey);
        intake("alice", alien, otherCase);
        String missing = uniqueKey("EV");

        MvcResult wrongCase = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev, alien), cutoffSoon());
        assertThat(wrongCase.getResponse().getStatus()).isEqualTo(422);
        assertThat(wrongCase.getResponse().getContentAsString()).contains("不属于案件");

        MvcResult notFound = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev, missing), cutoffSoon());
        assertThat(notFound.getResponse().getStatus()).isEqualTo(422);
        assertThat(notFound.getResponse().getContentAsString()).contains("证物不存在");

        // 失败不留半成品：案件下没有任何包版本
        MvcResult list = mockMvc.perform(get("/api/cases/{caseKey}/disclosure-packages", caseKey)).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(list.getResponse().getContentAsString())).isEmpty();
    }

    @Test
    void cutoffBeforeIntakeReturns422() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String pastCutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(1).toString();
        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), pastCutoff);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains("尚未入库");
    }

    @Test
    void failedCreateDoesNotOccupyCommandKeyOrVersion() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String commandKey = uniqueKey("CMD");

        MvcResult failed = createPackage("alice", commandKey, caseKey, List.of(ev),
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1).toString());
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);

        // 同一命令键修正参数后重试成功，失败不占键、不占版本
        MvcResult success = createPackage("alice", commandKey, caseKey, List.of(ev), cutoffSoon());
        assertThat(success.getResponse().getStatus()).isEqualTo(201);
        JsonNode pkg = objectMapper.readTree(success.getResponse().getContentAsString()).get("pkg");
        assertThat(pkg.get("packageVersion").asInt()).isEqualTo(1);
    }

    @Test
    void createIdempotentReplayReturnsSamePackage() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        String commandKey = uniqueKey("CMD");
        String cutoff = cutoffSoon();
        MvcResult first = createPackage("alice", commandKey, caseKey, List.of(ev), cutoff);
        MvcResult replay = createPackage("alice", commandKey, caseKey, List.of(ev), cutoff);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());

        MvcResult list = mockMvc.perform(get("/api/cases/{caseKey}/disclosure-packages", caseKey)).andReturn();
        assertThat(objectMapper.readTree(list.getResponse().getContentAsString())).hasSize(1);
    }

    @Test
    void sameCommandKeyDifferentParamsReturns409() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        String commandKey = uniqueKey("CMD");
        assertThat(createPackage("alice", commandKey, caseKey, List.of(ev1), cutoffSoon())
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(createPackage("alice", commandKey, caseKey, List.of(ev2), cutoffSoon())
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void adjustBuildsHigherRevisionAndKeepsBaseVersionUntouched() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        intake("alice", ev3, caseKey);
        String cutoff = cutoffSoon();
        MvcResult v1Result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev1, ev2), cutoff);
        String baseKey = objectMapper.readTree(v1Result.getResponse().getContentAsString())
                .get("pkg").get("packageKey").asText();

        Map<String, Object> adjustBody = new LinkedHashMap<>();
        adjustBody.put("commandKey", uniqueKey("CMD"));
        adjustBody.put("addEvidenceKeys", List.of(ev3));
        adjustBody.put("removeEvidenceKeys", List.of(ev1));
        MvcResult adjusted = mockMvc.perform(post("/api/disclosure-packages/{key}/adjust", baseKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjustBody)))
                .andReturn();
        assertThat(adjusted.getResponse().getStatus()).isEqualTo(201);
        JsonNode v2 = objectMapper.readTree(adjusted.getResponse().getContentAsString());
        assertThat(v2.get("pkg").get("packageVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("pkg").get("basePackageKey").asText()).isEqualTo(baseKey);
        assertThat(v2.get("items").findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrderElementsOf(List.of(ev2, ev3));
        // 逐件按字典序规范化
        String v2First = v2.get("items").get(0).get("evidenceKey").asText();
        String v2Second = v2.get("items").get(1).get("evidenceKey").asText();
        assertThat(v2First).isLessThan(v2Second);

        // 基准包内容不变
        JsonNode base = getPackage(baseKey);
        assertThat(base.get("pkg").get("packageVersion").asInt()).isEqualTo(1);
        assertThat(base.get("items").findValuesAsText("evidenceKey"))
                .containsExactlyInAnyOrderElementsOf(List.of(ev1, ev2));
    }

    @Test
    void adjustFailureDoesNotChangeExistingVersions() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        MvcResult v1Result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev1), cutoffSoon());
        String baseKey = objectMapper.readTree(v1Result.getResponse().getContentAsString())
                .get("pkg").get("packageKey").asText();

        // 删除包外证物：参数校验 400，不生成新版本
        Map<String, Object> badRemove = new LinkedHashMap<>();
        badRemove.put("commandKey", uniqueKey("CMD"));
        badRemove.put("addEvidenceKeys", List.of());
        badRemove.put("removeEvidenceKeys", List.of(uniqueKey("EV")));
        MvcResult badResult = mockMvc.perform(post("/api/disclosure-packages/{key}/adjust", baseKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badRemove)))
                .andReturn();
        assertThat(badResult.getResponse().getStatus()).isEqualTo(400);

        // 增删后空集合：400，不生成新版本
        Map<String, Object> emptyResult = new LinkedHashMap<>();
        emptyResult.put("commandKey", uniqueKey("CMD"));
        emptyResult.put("addEvidenceKeys", List.of());
        emptyResult.put("removeEvidenceKeys", List.of(ev1));
        MvcResult empty = mockMvc.perform(post("/api/disclosure-packages/{key}/adjust", baseKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyResult)))
                .andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);

        MvcResult list = mockMvc.perform(get("/api/cases/{caseKey}/disclosure-packages", caseKey)).andReturn();
        JsonNode packages = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(packages).hasSize(1);
        assertThat(packages.get(0).get("packageKey").asText()).isEqualTo(baseKey);
    }

    @Test
    void deliveredPackageCannotBeRevokedButCanSpawnRevision() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1, caseKey);
        intake("alice", ev2, caseKey);
        MvcResult v1Result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev1), cutoffSoon());
        String packageKey = objectMapper.readTree(v1Result.getResponse().getContentAsString())
                .get("pkg").get("packageKey").asText();

        assertThat(commandPost("/api/disclosure-packages/" + packageKey + "/deliver",
                "alice", uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(200);
        assertThat(commandPost("/api/disclosure-packages/" + packageKey + "/deliver",
                "alice", uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(409);

        Map<String, Object> revokeBody = new LinkedHashMap<>();
        revokeBody.put("commandKey", uniqueKey("CMD"));
        revokeBody.put("reason", "defect found");
        MvcResult revoke = mockMvc.perform(post("/api/disclosure-packages/{key}/revoke", packageKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revokeBody)))
                .andReturn();
        assertThat(revoke.getResponse().getStatus()).isEqualTo(409);

        // 已交付包发现问题：创建更高修订包
        Map<String, Object> adjustBody = new LinkedHashMap<>();
        adjustBody.put("commandKey", uniqueKey("CMD"));
        adjustBody.put("addEvidenceKeys", List.of(ev2));
        adjustBody.put("removeEvidenceKeys", List.of());
        MvcResult revision = mockMvc.perform(post("/api/disclosure-packages/{key}/adjust", packageKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjustBody)))
                .andReturn();
        assertThat(revision.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(revision.getResponse().getContentAsString())
                .get("pkg").get("packageVersion").asInt()).isEqualTo(2);

        JsonNode delivered = getPackage(packageKey);
        assertThat(delivered.get("pkg").get("status").asText()).isEqualTo("DELIVERED");
        assertThat(delivered.get("pkg").get("deliveredBy").asText()).isEqualTo("alice");
    }

    @Test
    void revokeFrozenPackageWritesReasonAndBlocksFurtherRevokeAndDeliver() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        MvcResult v1Result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoffSoon());
        String packageKey = objectMapper.readTree(v1Result.getResponse().getContentAsString())
                .get("pkg").get("packageKey").asText();

        Map<String, Object> revokeBody = new LinkedHashMap<>();
        revokeBody.put("commandKey", uniqueKey("CMD"));
        revokeBody.put("reason", "wrong evidence set");
        MvcResult revoked = mockMvc.perform(post("/api/disclosure-packages/{key}/revoke", packageKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revokeBody)))
                .andReturn();
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(revoked.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("REVOKED");

        Map<String, Object> secondRevoke = new LinkedHashMap<>();
        secondRevoke.put("commandKey", uniqueKey("CMD"));
        secondRevoke.put("reason", "again");
        MvcResult secondRevokeResult = mockMvc.perform(post("/api/disclosure-packages/{key}/revoke", packageKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRevoke)))
                .andReturn();
        assertThat(secondRevokeResult.getResponse().getStatus()).isEqualTo(409);
        assertThat(commandPost("/api/disclosure-packages/" + packageKey + "/deliver",
                "alice", uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(409);

        MvcResult revocation = mockMvc.perform(
                get("/api/disclosure-packages/{key}/revocation", packageKey)).andReturn();
        assertThat(revocation.getResponse().getStatus()).isEqualTo(200);
        JsonNode revocationBody = objectMapper.readTree(revocation.getResponse().getContentAsString());
        assertThat(revocationBody.get("reason").asText()).isEqualTo("wrong evidence set");
        assertThat(revocationBody.get("revokedBy").asText()).isEqualTo("alice");
    }

    @Test
    void revocationOfDeliveredPackageRequestWithoutReasonReturns400() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        MvcResult v1Result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoffSoon());
        String packageKey = objectMapper.readTree(v1Result.getResponse().getContentAsString())
                .get("pkg").get("packageKey").asText();
        Map<String, Object> revokeBody = new LinkedHashMap<>();
        revokeBody.put("commandKey", uniqueKey("CMD"));
        MvcResult result = mockMvc.perform(post("/api/disclosure-packages/{key}/revoke", packageKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(revokeBody)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void diagnosisReportsContinuousChainAndCutoffFlags() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        transfer("alice", "bob", ev);
        MvcResult created = createPackage("bob", uniqueKey("CMD"), caseKey, List.of(ev), cutoffSoon());
        String packageKey = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("pkg").get("packageKey").asText();

        MvcResult diagnosis = mockMvc.perform(
                get("/api/disclosure-packages/{key}/diagnosis", packageKey)).andReturn();
        assertThat(diagnosis.getResponse().getStatus()).isEqualTo(200);
        JsonNode diagnoses = objectMapper.readTree(diagnosis.getResponse().getContentAsString());
        assertThat(diagnoses).hasSize(1);
        JsonNode d = diagnoses.get(0);
        assertThat(d.get("chainContinuous").asBoolean()).isTrue();
        assertThat(d.get("gaps")).isEmpty();
        assertThat(d.get("custodianIdAtCutoff").asText()).isEqualTo("bob");
        assertThat(d.get("pendingTransferAtCutoff").asBoolean()).isFalse();
        assertThat(d.get("borrowedUnreturnedAtCutoff").asBoolean()).isFalse();
        assertThat(d.get("sealOkAtCutoff").asBoolean()).isTrue();
    }

    @Test
    void chainGapInsertedInHistoryReturns422WithDistinguishableReason() throws Exception {
        String caseKey = uniqueKey("CASE");
        String ev = uniqueKey("EV");
        intake("alice", ev, caseKey);
        // 直接写入一条与在任保管人不衔接的历史交接（模拟链缺口数据），其时间早于截至时刻
        jdbcTemplate.update("""
                        INSERT INTO transfer_record
                            (evidence_key, from_custodian, to_custodian, status, initiated_by, created_at, decided_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL)
                        """,
                ev, "nobody", "zoe", TransferStatus.PENDING.name(), "nobody",
                LocalDateTime.now().minusSeconds(30));

        MvcResult result = createPackage("alice", uniqueKey("CMD"), caseKey, List.of(ev), cutoffSoon());
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        String message = result.getResponse().getContentAsString();
        assertThat(message).contains("缺口");
    }

    @Test
    void missingPackageQueriesReturn404() throws Exception {
        String missing = uniqueKey("PKG");
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{key}", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{key}/diagnosis", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/disclosure-packages/{key}/revocation", missing)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }
}
