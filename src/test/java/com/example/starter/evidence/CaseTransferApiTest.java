package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 证物跨案移交 API 测试：主流程、双案一致封存、失败分支（400/403/404/409/422）、
 * 整批原子性、幂等重放与撤销边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseTransferApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String VALID_FROM = "2020-01-01T00:00:00";
    private static final String VALID_TO = "2099-12-31T23:59:59";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    // ---------- 合成数据准备 ----------

    private void registerOrder(String orderVersion) throws Exception {
        registerOrder(orderVersion, VALID_FROM, VALID_TO);
    }

    private void registerOrder(String orderVersion, String validFrom, String validTo)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderVersion", orderVersion);
        body.put("validFrom", validFrom);
        body.put("validTo", validTo);
        MvcResult result = mockMvc.perform(post("/api/evidence/transfer-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void grant(String caseKey, String custodianId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("custodianId", custodianId);
        MvcResult result = mockMvc.perform(post("/api/evidence/cases/{caseKey}/custodians", caseKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private void intake(String actor, String evidenceKey, String caseKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", caseKey);
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    /**
     * 准备一个标准移交场景：来源/目标案件、双方保管人授权、有效令版本、若干证物。
     */
    private record Fixture(String sourceCase, String targetCase, String orderVersion,
                           String sourceCustodian, String targetCustodian) {
    }

    private Fixture newFixture() throws Exception {
        Fixture f = new Fixture(uniqueKey("CASE-SRC"), uniqueKey("CASE-TGT"),
                uniqueKey("ORD"), "alice", "bob");
        registerOrder(f.orderVersion());
        grant(f.sourceCase(), f.sourceCustodian());
        grant(f.targetCase(), f.targetCustodian());
        return f;
    }

    private MvcResult caseTransfer(String actor, Fixture f, String commandKey,
                                   List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("sourceCaseKey", f.sourceCase());
        body.put("targetCaseKey", f.targetCase());
        body.put("orderVersion", f.orderVersion());
        body.put("sourceCustodianId", f.sourceCustodian());
        body.put("targetCustodianId", f.targetCustodian());
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/evidence/case-transfers")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult revoke(String actor, String transferId, String commandKey,
                             String orderVersion, String sourceConfirmer,
                             String targetConfirmer) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("orderVersion", orderVersion);
        body.put("sourceCustodianId", sourceConfirmer);
        body.put("targetCustodianId", targetConfirmer);
        return mockMvc.perform(post("/api/evidence/case-transfers/{id}/revoke", transferId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode getTransfer(String transferId) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/case-transfers/{id}", transferId))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode caseLinks(String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/cases/{caseKey}/case-links", caseKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode caseTransfers(String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/cases/{caseKey}/case-transfers", caseKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "examination");
        body.put("dueAt", java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(24)
                .toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateCustodyTransfer(String actor, String evidenceKey, String to)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", to);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
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

    // ---------- 主流程 ----------

    @Test
    void caseTransferSealsDualSnapshotAndMovesBatchAtomically() throws Exception {
        Fixture f = newFixture();
        String ev1 = uniqueKey("EV-B");
        String ev2 = uniqueKey("EV-A");
        intake(f.sourceCustodian(), ev1, f.sourceCase());
        intake(f.sourceCustodian(), ev2, f.sourceCase());

        // 故意乱序提交，服务端应规范排序
        MvcResult result = caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"),
                List.of(ev1, ev2));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(result.getResponse().getContentAsString());

        String transferId = view.get("transferId").asText();
        assertThat(transferId).startsWith("CT-");
        assertThat(view.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(view.get("sourceCaseKey").asText()).isEqualTo(f.sourceCase());
        assertThat(view.get("targetCaseKey").asText()).isEqualTo(f.targetCase());
        assertThat(view.get("orderVersion").asText()).isEqualTo(f.orderVersion());
        assertThat(view.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(view.get("revokedAt").isNull()).isTrue();
        // 快照按证物键字典序固化双方案件、原位置、封签版本与令版本
        JsonNode items = view.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("evidenceKey").asText()).isEqualTo(ev2);
        assertThat(items.get(1).get("evidenceKey").asText()).isEqualTo(ev1);
        for (JsonNode item : items) {
            assertThat(item.get("sourceCaseKey").asText()).isEqualTo(f.sourceCase());
            assertThat(item.get("targetCaseKey").asText()).isEqualTo(f.targetCase());
            assertThat(item.get("fromLocation").isNull()).isTrue();
            assertThat(item.get("sealVersion").asInt()).isEqualTo(1);
            assertThat(item.get("orderVersion").asText()).isEqualTo(f.orderVersion());
        }

        // 目标案件承接证物：归属与保管人已原子切换
        JsonNode chain = chain(ev1);
        assertThat(chain.get("evidence").get("caseKey").asText()).isEqualTo(f.targetCase());
        assertThat(chain.get("evidence").get("custodianId").asText())
                .isEqualTo(f.targetCustodian());
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain.get("caseLinks")).hasSize(2);

        // 双案链：来源移出、目标移入，共用同一 transferId
        JsonNode sourceLinks = caseLinks(f.sourceCase());
        JsonNode targetLinks = caseLinks(f.targetCase());
        assertThat(sourceLinks).hasSize(2);
        assertThat(targetLinks).hasSize(2);
        assertThat(sourceLinks.findValuesAsText("direction")).containsOnly("OUT");
        assertThat(targetLinks.findValuesAsText("direction")).containsOnly("IN");
        assertThat(sourceLinks.findValuesAsText("transferId")).containsOnly(transferId);
        assertThat(targetLinks.findValuesAsText("transferId")).containsOnly(transferId);

        // 历史查询：双方案件均可见该批次
        assertThat(caseTransfers(f.sourceCase())).hasSize(1);
        assertThat(caseTransfers(f.targetCase())).hasSize(1);

        // 诊断查询：实际数量、令版本有效性与双案链条数
        MvcResult diag = mockMvc.perform(
                        get("/api/evidence/case-transfers/{id}/diagnostics", transferId))
                .andReturn();
        assertThat(diag.getResponse().getStatus()).isEqualTo(200);
        JsonNode diagBody = objectMapper.readTree(diag.getResponse().getContentAsString());
        assertThat(diagBody.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(diagBody.get("orderValidNow").asBoolean()).isTrue();
        assertThat(diagBody.get("orderRemainingSeconds").asLong()).isPositive();
        assertThat(diagBody.get("sourceLinkCount").asInt()).isEqualTo(2);
        assertThat(diagBody.get("targetLinkCount").asInt()).isEqualTo(2);
    }

    @Test
    void targetCaseHandlesLoanAndReturnAfterTransfer() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(200);

        // 目标保管人可借出；来源保管人不再有权借出
        assertThat(borrow(f.sourceCustodian(), ev, "carol").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(borrow(f.targetCustodian(), ev, "carol").getResponse().getStatus())
                .isEqualTo(200);
        JsonNode chain = chain(ev);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("BORROWED");
        assertThat(chain.get("loans")).hasSize(1);
    }

    // ---------- 参数与权限失败分支 ----------

    @Test
    void sameSourceAndTargetCaseReturns400() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("sourceCaseKey", f.sourceCase());
        body.put("targetCaseKey", f.sourceCase());
        body.put("orderVersion", f.orderVersion());
        body.put("sourceCustodianId", f.sourceCustodian());
        body.put("targetCustodianId", f.targetCustodian());
        body.put("evidenceKeys", List.of(ev));
        MvcResult result = mockMvc.perform(post("/api/evidence/case-transfers")
                        .header(ACTOR_HEADER, f.sourceCustodian())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void duplicateEvidenceKeysReturns400() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"), List.of(ev, ev))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void invalidOrExpiredOrderVersionReturns403() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());

        // 令版本不存在
        Fixture unknown = new Fixture(f.sourceCase(), f.targetCase(), uniqueKey("ORD-UNKNOWN"),
                f.sourceCustodian(), f.targetCustodian());
        assertThat(caseTransfer(f.sourceCustodian(), unknown, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);

        // 令版本已过期（右开区间之外）
        String expired = uniqueKey("ORD-EXP");
        registerOrder(expired, "2020-01-01T00:00:00", "2021-01-01T00:00:00");
        Fixture exp = new Fixture(f.sourceCase(), f.targetCase(), expired,
                f.sourceCustodian(), f.targetCustodian());
        assertThat(caseTransfer(f.sourceCustodian(), exp, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);

        // 令版本尚未生效（左闭区间之外）
        String future = uniqueKey("ORD-FUT");
        registerOrder(future, "2098-01-01T00:00:00", "2099-01-01T00:00:00");
        Fixture fut = new Fixture(f.sourceCase(), f.targetCase(), future,
                f.sourceCustodian(), f.targetCustodian());
        assertThat(caseTransfer(f.sourceCustodian(), fut, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);

        // 失败不写入任何链
        assertThat(caseLinks(f.sourceCase())).isEmpty();
        assertThat(caseLinks(f.targetCase())).isEmpty();
    }

    @Test
    void sameCustodianOnBothSidesReturns403() throws Exception {
        Fixture f = new Fixture(uniqueKey("CASE-SRC"), uniqueKey("CASE-TGT"),
                uniqueKey("ORD"), "alice", "alice");
        registerOrder(f.orderVersion());
        grant(f.sourceCase(), "alice");
        grant(f.targetCase(), "alice");
        String ev = uniqueKey("EV");
        intake("alice", ev, f.sourceCase());
        assertThat(caseTransfer("alice", f, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void custodianWithoutCasePermissionReturns403() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());

        // 提交人不是来源保管人
        assertThat(caseTransfer("mallory", f, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);

        // 目标保管人无目标案件权限
        Fixture noTargetPerm = new Fixture(f.sourceCase(), f.targetCase(), f.orderVersion(),
                f.sourceCustodian(), "mallory");
        assertThat(caseTransfer(f.sourceCustodian(), noTargetPerm, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);

        // 来源保管人无来源案件权限（证物实际保管人是 alice，但 carol 被声明为来源保管人）
        Fixture noSourcePerm = new Fixture(f.sourceCase(), f.targetCase(), f.orderVersion(),
                "carol", f.targetCustodian());
        assertThat(caseTransfer("carol", noSourcePerm, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- 证物状态失败分支（422，整批原子） ----------

    @Test
    void evidenceNotBelongingToSourceCaseReturns422AndWritesNothing() throws Exception {
        Fixture f = newFixture();
        String evInSource = uniqueKey("EV");
        String evElsewhere = uniqueKey("EV");
        intake(f.sourceCustodian(), evInSource, f.sourceCase());
        intake(f.sourceCustodian(), evElsewhere, uniqueKey("CASE-OTHER"));

        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"),
                List.of(evInSource, evElsewhere)).getResponse().getStatus()).isEqualTo(422);

        // 整批不写入任何保管链，合规证物仍留在来源案件
        assertThat(caseLinks(f.sourceCase())).isEmpty();
        assertThat(caseLinks(f.targetCase())).isEmpty();
        assertThat(caseTransfers(f.sourceCase())).isEmpty();
        JsonNode chain = chain(evInSource);
        assertThat(chain.get("evidence").get("caseKey").asText()).isEqualTo(f.sourceCase());
        assertThat(chain.get("evidence").get("custodianId").asText())
                .isEqualTo(f.sourceCustodian());
    }

    @Test
    void missingEvidenceReturns422() throws Exception {
        Fixture f = newFixture();
        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"),
                List.of(uniqueKey("EV-MISSING"))).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void borrowedEvidenceReturns422AndBatchRollsBack() throws Exception {
        Fixture f = newFixture();
        String evOk = uniqueKey("EV");
        String evBorrowed = uniqueKey("EV");
        intake(f.sourceCustodian(), evOk, f.sourceCase());
        intake(f.sourceCustodian(), evBorrowed, f.sourceCase());
        assertThat(borrow(f.sourceCustodian(), evBorrowed, "carol").getResponse().getStatus())
                .isEqualTo(200);

        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"),
                List.of(evOk, evBorrowed)).getResponse().getStatus()).isEqualTo(422);
        assertThat(chain(evOk).get("evidence").get("caseKey").asText())
                .isEqualTo(f.sourceCase());
        assertThat(caseLinks(f.targetCase())).isEmpty();
    }

    @Test
    void sealBrokenEvidenceReturns422() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        assertThat(inspect(f.sourceCustodian(), ev, false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void evidenceInPendingCustodyTransferReturns422() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        assertThat(initiateCustodyTransfer(f.sourceCustodian(), ev, "carol")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void evidenceCustodianMismatchReturns422() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        // 证物由 carol 保管，但请求声明来源保管人为 alice
        intake("carol", ev, f.sourceCase());
        grant(f.sourceCase(), "carol");
        assertThat(caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"), List.of(ev))
                .getResponse().getStatus()).isEqualTo(422);
    }

    // ---------- 幂等 ----------

    @Test
    void idempotentReplayReturnsFirstSnapshotAndDoesNotDuplicate() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String commandKey = uniqueKey("CMD");

        MvcResult first = caseTransfer(f.sourceCustodian(), f, commandKey, List.of(ev));
        MvcResult replay = caseTransfer(f.sourceCustodian(), f, commandKey, List.of(ev));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 重放不产生额外批次或链事件
        assertThat(caseTransfers(f.sourceCase())).hasSize(1);
        assertThat(caseLinks(f.sourceCase())).hasSize(1);
        assertThat(caseLinks(f.targetCase())).hasSize(1);
    }

    @Test
    void sameCommandKeyWithDifferentParamsReturns409() throws Exception {
        Fixture f = newFixture();
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake(f.sourceCustodian(), ev1, f.sourceCase());
        intake(f.sourceCustodian(), ev2, f.sourceCase());
        String commandKey = uniqueKey("CMD");
        assertThat(caseTransfer(f.sourceCustodian(), f, commandKey, List.of(ev1))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(caseTransfer(f.sourceCustodian(), f, commandKey, List.of(ev2))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedRequestDoesNotOccupyCommandKey() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String commandKey = uniqueKey("CMD");

        // 先以失效令版本失败（403），同键修正参数后应可成功
        Fixture badOrder = new Fixture(f.sourceCase(), f.targetCase(), uniqueKey("ORD-UNKNOWN"),
                f.sourceCustodian(), f.targetCustodian());
        assertThat(caseTransfer(f.sourceCustodian(), badOrder, commandKey, List.of(ev))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(caseTransfer(f.sourceCustodian(), f, commandKey, List.of(ev))
                .getResponse().getStatus()).isEqualTo(200);
    }

    // ---------- 撤销 ----------

    @Test
    void revokeAppendsReverseLinksAndRestoresSourceCustody() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        MvcResult transferred = caseTransfer(f.sourceCustodian(), f, uniqueKey("CMD"),
                List.of(ev));
        String transferId = objectMapper.readTree(transferred.getResponse().getContentAsString())
                .get("transferId").asText();

        MvcResult revoked = revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian());
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        JsonNode view = objectMapper.readTree(revoked.getResponse().getContentAsString());
        assertThat(view.get("status").asText()).isEqualTo("REVOKED");
        assertThat(view.get("revokedAt").isNull()).isFalse();
        assertThat(view.get("revokeSourceCustodianId").asText()).isEqualTo(f.sourceCustodian());
        assertThat(view.get("revokeTargetCustodianId").asText()).isEqualTo(f.targetCustodian());

        // 证物回到来源案件与来源保管人
        JsonNode chain = chain(ev);
        assertThat(chain.get("evidence").get("caseKey").asText()).isEqualTo(f.sourceCase());
        assertThat(chain.get("evidence").get("custodianId").asText())
                .isEqualTo(f.sourceCustodian());
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 原移交链保留，双方各追加一条反向链
        JsonNode sourceLinks = caseLinks(f.sourceCase());
        JsonNode targetLinks = caseLinks(f.targetCase());
        assertThat(sourceLinks.findValuesAsText("direction"))
                .containsExactly("OUT", "REVOKE_IN");
        assertThat(targetLinks.findValuesAsText("direction"))
                .containsExactly("IN", "REVOKE_OUT");

        // 原批次记录与快照不删除
        JsonNode detail = getTransfer(transferId);
        assertThat(detail.get("status").asText()).isEqualTo("REVOKED");
        assertThat(detail.get("items")).hasSize(1);

        // 重复撤销返回 409
        assertThat(revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void revokeBlockedAfterSubsequentHandoverInTargetCase() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String transferId = objectMapper.readTree(caseTransfer(f.sourceCustodian(), f,
                        uniqueKey("CMD"), List.of(ev)).getResponse().getContentAsString())
                .get("transferId").asText();

        // 目标案件发生后续交接（即使尚未被接受）
        grant(f.targetCase(), "carol");
        assertThat(initiateCustodyTransfer(f.targetCustodian(), ev, "carol")
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(409);

        // 撤销失败不改变状态
        assertThat(getTransfer(transferId).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(caseLinks(f.sourceCase()).findValuesAsText("direction"))
                .containsExactly("OUT");
    }

    @Test
    void revokeBlockedWhileEvidenceBorrowedReturns422() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String transferId = objectMapper.readTree(caseTransfer(f.sourceCustodian(), f,
                        uniqueKey("CMD"), List.of(ev)).getResponse().getContentAsString())
                .get("transferId").asText();
        assertThat(borrow(f.targetCustodian(), ev, "carol").getResponse().getStatus())
                .isEqualTo(200);

        assertThat(revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void revokeValidationFailures() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String transferId = objectMapper.readTree(caseTransfer(f.sourceCustodian(), f,
                        uniqueKey("CMD"), List.of(ev)).getResponse().getContentAsString())
                .get("transferId").asText();

        // 移交不存在
        assertThat(revoke(f.sourceCustodian(), uniqueKey("CT-MISSING"), uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(404);

        // 双方确认人相同
        assertThat(revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.sourceCustodian())
                .getResponse().getStatus()).isEqualTo(403);

        // 提交人不是双方确认人之一
        assertThat(revoke("mallory", transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(403);

        // 确认人不具备对应案件权限
        assertThat(revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), "mallory", f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(403);

        // 令版本与原移交不一致
        String otherOrder = uniqueKey("ORD");
        registerOrder(otherOrder);
        assertThat(revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                otherOrder, f.sourceCustodian(), f.targetCustodian())
                .getResponse().getStatus()).isEqualTo(409);

        // 以上失败均不改变批次状态
        assertThat(getTransfer(transferId).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void revokeIdempotentReplayReturnsSameResult() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String transferId = objectMapper.readTree(caseTransfer(f.sourceCustodian(), f,
                        uniqueKey("CMD"), List.of(ev)).getResponse().getContentAsString())
                .get("transferId").asText();
        String commandKey = uniqueKey("CMD");
        MvcResult first = revoke(f.sourceCustodian(), transferId, commandKey,
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian());
        MvcResult replay = revoke(f.sourceCustodian(), transferId, commandKey,
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian());
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 反向链只追加一次
        assertThat(caseLinks(f.sourceCase()).findValuesAsText("direction"))
                .containsExactly("OUT", "REVOKE_IN");
    }

    // ---------- 只读查询 ----------

    @Test
    void readQueriesDoNotChangeState() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String transferId = objectMapper.readTree(caseTransfer(f.sourceCustodian(), f,
                        uniqueKey("CMD"), List.of(ev)).getResponse().getContentAsString())
                .get("transferId").asText();

        JsonNode before = getTransfer(transferId);
        mockMvc.perform(get("/api/evidence/case-transfers/{id}/diagnostics", transferId))
                .andReturn();
        mockMvc.perform(get("/api/evidence/cases/{caseKey}/case-transfers", f.sourceCase()))
                .andReturn();
        mockMvc.perform(get("/api/evidence/cases/{caseKey}/case-links", f.targetCase()))
                .andReturn();
        mockMvc.perform(get("/api/evidence/{key}/case-links", ev)).andReturn();
        JsonNode after = getTransfer(transferId);

        assertThat(after).isEqualTo(before);
        assertThat(chain(ev).get("evidence").get("caseKey").asText())
                .isEqualTo(f.targetCase());
    }

    @Test
    void unknownTransferReturns404() throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/case-transfers/{id}", uniqueKey("CT-MISSING")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        MvcResult diag = mockMvc.perform(
                        get("/api/evidence/case-transfers/{id}/diagnostics",
                                uniqueKey("CT-MISSING")))
                .andReturn();
        assertThat(diag.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void evidenceCaseLinksHistoryAcrossCases() throws Exception {
        Fixture f = newFixture();
        String ev = uniqueKey("EV");
        intake(f.sourceCustodian(), ev, f.sourceCase());
        String transferId = objectMapper.readTree(caseTransfer(f.sourceCustodian(), f,
                        uniqueKey("CMD"), List.of(ev)).getResponse().getContentAsString())
                .get("transferId").asText();
        revoke(f.sourceCustodian(), transferId, uniqueKey("CMD"),
                f.orderVersion(), f.sourceCustodian(), f.targetCustodian());

        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/case-links", ev))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode links = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(links).hasSize(4);
        List<String> directions = new ArrayList<>();
        links.forEach(node -> directions.add(node.get("direction").asText()));
        assertThat(directions).containsExactly("OUT", "IN", "REVOKE_OUT", "REVOKE_IN");
        // 全部链事件共用同一 transferId
        assertThat(links.findValuesAsText("transferId")).containsOnly(transferId);
    }
}
