package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 保留冻结与销毁申请 API 测试：主流程、拒绝分支、窗口左闭右开边界、
 * 解除链、幂等重放与改参 409、历史 DENIED 不改写、两级冻结查询。
 * 全部时间通过固定时钟控制，无需真实等待；数据写入真实 H2（MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HoldApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    /** 合成验收基准时刻：案件冻结至 UTC 12:00，11:59 拒绝，12:00 恰好放行。 */
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 10, 1, 12, 0, 0);
    private static final LocalDateTime BEFORE_NOON = NOON.minusMinutes(1);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private void fixClock(LocalDateTime utc) {
        evidenceClock.setClock(Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private MvcResult intake(String actor, String commandKey, String evidenceKey, String caseKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
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

    private MvcResult caseHold(String actor, String commandKey, String caseKey,
                               LocalDateTime expiresAt, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("expiresAt", expiresAt.toString());
        body.put("reason", reason);
        return mockMvc.perform(post("/api/cases/{caseKey}/retention-holds", caseKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult evidenceHold(String actor, String commandKey, String evidenceKey,
                                   LocalDateTime expiresAt, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("expiresAt", expiresAt.toString());
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/{key}/retention-holds", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult release(String actor, String commandKey, String holdRequestId, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/retention-holds/{id}/release", holdRequestId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult destroy(String actor, String commandKey, String evidenceKey, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/{key}/destruction", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void caseHoldCreatedWithUtcExpiryAndReason() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        MvcResult result = caseHold("bob", uniqueKey("HOLD"), caseKey, NOON, "诉讼保留至中午");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("level").asText()).isEqualTo("CASE");
        assertThat(body.get("caseKey").asText()).isEqualTo(caseKey);
        assertThat(body.get("scopeKey").asText()).isEqualTo(caseKey);
        assertThat(body.get("evidenceKey").isNull()).isTrue();
        assertThat(body.get("expiresAt").asText()).isEqualTo("2026-10-01T12:00:00");
        assertThat(body.get("reason").asText()).isEqualTo("诉讼保留至中午");
        assertThat(body.get("createdBy").asText()).isEqualTo("bob");
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("effective").asBoolean()).isTrue();
        assertThat(body.get("releasedAt").isNull()).isTrue();
    }

    @Test
    void caseHoldBlocksDestructionAt1159AndExpiresExactlyAtNoon() throws Exception {
        // 合成验收：案件 K 冻结至 12:00；所属证物 A 在 11:59 申请销毁返回 409；
        // 12:00 且无其他冻结时可用新申请销毁。
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE-K");
        String evidenceKey = uniqueKey("EV-A");
        assertThat(intake("alice", uniqueKey("CMD"), evidenceKey, caseKey)
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult holdResult = caseHold("bob", uniqueKey("HOLD"), caseKey, NOON, "案件K诉讼保留");
        assertThat(holdResult.getResponse().getStatus()).isEqualTo(201);

        String deniedKey = uniqueKey("DES");
        MvcResult denied = destroy("alice", deniedKey, evidenceKey, "保管到期销毁");
        assertThat(denied.getResponse().getStatus()).isEqualTo(409);
        JsonNode deniedBody = objectMapper.readTree(denied.getResponse().getContentAsString());
        assertThat(deniedBody.get("status").asText()).isEqualTo("DENIED");
        assertThat(deniedBody.get("blockingHoldId").isNull()).isFalse();
        assertThat(deniedBody.get("blockingHoldId").asLong()).isPositive();

        // 证物状态不变，仍为 SEALED 且保管人不变
        JsonNode chainAt1159 = chain(evidenceKey);
        assertThat(chainAt1159.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chainAt1159.get("evidence").get("custodianId").asText()).isEqualTo("alice");

        // 时钟走到恰好 12:00：左闭右开，冻结不再阻断
        fixClock(NOON);
        MvcResult allowed = destroy("alice", uniqueKey("DES"), evidenceKey, "保管到期销毁");
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        JsonNode allowedBody = objectMapper.readTree(allowed.getResponse().getContentAsString());
        assertThat(allowedBody.get("status").asText()).isEqualTo("ALLOWED");
        assertThat(allowedBody.get("blockingHoldId").isNull()).isTrue();

        JsonNode chainAtNoon = chain(evidenceKey);
        assertThat(chainAtNoon.get("evidence").get("status").asText()).isEqualTo("DESTROYED");
        // 历史 DENIED 不被到期或成功销毁改写
        assertThat(chainAtNoon.get("destructions")).hasSize(2);
        assertThat(chainAtNoon.get("destructions").get(0).get("status").asText()).isEqualTo("DENIED");
        assertThat(chainAtNoon.get("destructions").get(0).get("requestId").asText())
                .isEqualTo(deniedKey);
        assertThat(chainAtNoon.get("destructions").get(1).get("status").asText()).isEqualTo("ALLOWED");
        // 冻结行保留为 ACTIVE，但查询时刻 effective=false
        assertThat(chainAtNoon.get("holds").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(chainAtNoon.get("holds").get(0).get("effective").asBoolean()).isFalse();
    }

    @Test
    void evidenceHoldBlocksOnlyThatEvidenceAndCaseHoldCoversAllEvidence() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev1, caseKey);
        intake("alice", uniqueKey("CMD"), ev2, caseKey);

        assertThat(evidenceHold("bob", uniqueKey("HOLD"), ev1, NOON, "单件复核保留")
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(destroy("alice", uniqueKey("DES"), ev1, "销毁").getResponse().getStatus())
                .isEqualTo(409);
        // 同案件另一件证物没有自身冻结，也没有案件级冻结：可销毁
        assertThat(destroy("alice", uniqueKey("DES"), ev2, "销毁").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(chain(ev1).get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chain(ev2).get("evidence").get("status").asText()).isEqualTo("DESTROYED");

        // 再补案件级冻结：ev1 仍受阻断
        assertThat(caseHold("bob", uniqueKey("HOLD"), caseKey, NOON, "案件冻结")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(destroy("alice", uniqueKey("DES"), ev1, "销毁").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void releaseByDifferentCustodianWithReasonOnlyAffectsLaterRequests() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);
        String holdId = uniqueKey("HOLD");
        MvcResult holdResult = caseHold("bob", holdId, caseKey, NOON, "诉讼保留");
        assertThat(holdResult.getResponse().getStatus()).isEqualTo(201);

        // 解除前先被拒一次并留痕
        assertThat(destroy("alice", uniqueKey("DES"), evidenceKey, "销毁")
                .getResponse().getStatus()).isEqualTo(409);

        // 创建人本人不能解除
        assertThat(release("bob", uniqueKey("REL"), holdId, "案件结案").getResponse().getStatus())
                .isEqualTo(409);

        // 不同于创建人的保管人解除，须签明原因
        MvcResult released = release("carol", uniqueKey("REL"), holdId, "案件结案，解除保留");
        assertThat(released.getResponse().getStatus()).isEqualTo(200);
        JsonNode releasedBody = objectMapper.readTree(released.getResponse().getContentAsString());
        assertThat(releasedBody.get("status").asText()).isEqualTo("RELEASED");
        assertThat(releasedBody.get("releasedBy").asText()).isEqualTo("carol");
        assertThat(releasedBody.get("releaseReason").asText()).isEqualTo("案件结案，解除保留");
        assertThat(LocalDateTime.parse(releasedBody.get("releasedAt").asText()))
                .isEqualTo(BEFORE_NOON);
        assertThat(releasedBody.get("effective").asBoolean()).isFalse();

        // 解除只影响其后的销毁申请：新申请获批
        assertThat(destroy("alice", uniqueKey("DES"), evidenceKey, "解除后销毁")
                .getResponse().getStatus()).isEqualTo(200);
        // 过去被拒的申请仍为 DENIED
        JsonNode chainAfter = chain(evidenceKey);
        assertThat(chainAfter.get("evidence").get("status").asText()).isEqualTo("DESTROYED");
        assertThat(chainAfter.get("destructions").get(0).get("status").asText()).isEqualTo("DENIED");
        assertThat(chainAfter.get("destructions").get(1).get("status").asText()).isEqualTo("ALLOWED");
    }

    @Test
    void holdIdempotentReplayReturnsFirstResultAndChangedParamsReturn409() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String holdId = uniqueKey("HOLD");
        MvcResult first = caseHold("bob", holdId, caseKey, NOON, "诉讼保留");
        MvcResult replay = caseHold("bob", holdId, caseKey, NOON, "诉讼保留");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同 requestId 改到期时间 → 409
        assertThat(caseHold("bob", holdId, caseKey, NOON.plusHours(1), "诉讼保留")
                .getResponse().getStatus()).isEqualTo(409);
        // 同 requestId 换对象（另一案件）→ 409
        assertThat(caseHold("bob", holdId, uniqueKey("CASE-OTHER"), NOON, "诉讼保留")
                .getResponse().getStatus()).isEqualTo(409);

        // 证物级冻结同 requestId 换证物 → 409
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String evHoldId = uniqueKey("HOLD");
        intake("alice", uniqueKey("CMD"), ev1, caseKey);
        intake("alice", uniqueKey("CMD"), ev2, caseKey);
        assertThat(evidenceHold("bob", evHoldId, ev1, NOON, "证物冻结").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(evidenceHold("bob", evHoldId, ev2, NOON, "证物冻结").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void destructionIdempotentReplayKeepsFirstDeniedThenAllowedVerdict() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);
        caseHold("bob", uniqueKey("HOLD"), caseKey, NOON, "诉讼保留");

        String deniedRequestId = uniqueKey("DES");
        MvcResult first = destroy("alice", deniedRequestId, evidenceKey, "销毁");
        MvcResult replay = destroy("alice", deniedRequestId, evidenceKey, "销毁");
        assertThat(first.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复留痕
        assertThat(chain(evidenceKey).get("destructions")).hasSize(1);

        // 到期后新 requestId 获批；再次重放成功申请仍返回首次 200
        fixClock(NOON);
        String allowedRequestId = uniqueKey("DES");
        assertThat(destroy("alice", allowedRequestId, evidenceKey, "销毁").getResponse().getStatus())
                .isEqualTo(200);
        MvcResult allowedReplay = destroy("alice", allowedRequestId, evidenceKey, "销毁");
        assertThat(allowedReplay.getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("destructions")).hasSize(2);

        // 已销毁后旧的 DENIED requestId 重放仍是首次 409，证物保持 DESTROYED
        MvcResult deniedReplayAfter = destroy("alice", deniedRequestId, evidenceKey, "销毁");
        assertThat(deniedReplayAfter.getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("DESTROYED");
    }

    @Test
    void invalidInputsReturn400AndDoNotChangeState() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);

        // 到期时刻不晚于当前时刻
        assertThat(caseHold("bob", uniqueKey("HOLD"), caseKey, BEFORE_NOON, "过去到期")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(evidenceHold("bob", uniqueKey("HOLD"), evidenceKey,
                BEFORE_NOON.minusSeconds(1), "过去到期").getResponse().getStatus()).isEqualTo(400);
        // 缺少原因
        Map<String, Object> noReason = new LinkedHashMap<>();
        noReason.put("commandKey", uniqueKey("HOLD"));
        noReason.put("expiresAt", NOON.toString());
        MvcResult missingReason = mockMvc.perform(
                        post("/api/cases/{caseKey}/retention-holds", caseKey)
                                .header(ACTOR_HEADER, "bob")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(noReason)))
                .andReturn();
        assertThat(missingReason.getResponse().getStatus()).isEqualTo(400);
        // 销毁原因缺失
        Map<String, Object> noDestroyReason = new LinkedHashMap<>();
        noDestroyReason.put("commandKey", uniqueKey("DES"));
        MvcResult missingDestroyReason = mockMvc.perform(
                        post("/api/evidence/{key}/destruction", evidenceKey)
                                .header(ACTOR_HEADER, "alice")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(noDestroyReason)))
                .andReturn();
        assertThat(missingDestroyReason.getResponse().getStatus()).isEqualTo(400);
        // 失败后证物仍可销毁（无有效冻结）
        fixClock(NOON);
        assertThat(destroy("alice", uniqueKey("DES"), evidenceKey, "正常销毁").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void destroyRejectedForNonCustodianAndNonSealedStates() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);

        // 非当前保管人
        assertThat(destroy("mallory", uniqueKey("DES"), evidenceKey, "销毁").getResponse().getStatus())
                .isEqualTo(409);

        // 借出中（BORROWED）不可销毁
        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("LOAN"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "bob");
        loanBody.put("purpose", "鉴定");
        loanBody.put("dueAt", NOON.plusHours(1).toString());
        MvcResult loanResult = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();
        assertThat(loanResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(destroy("alice", uniqueKey("DES"), evidenceKey, "销毁").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("BORROWED");

        // 对不存在证物销毁返回 404
        assertThat(destroy("alice", uniqueKey("DES"), uniqueKey("MISSING"), "销毁")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void cannotHoldOrDestroyDestroyedEvidenceAndCannotReleaseTwiceOrByMissingHold() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);
        assertThat(destroy("alice", uniqueKey("DES"), evidenceKey, "销毁").getResponse().getStatus())
                .isEqualTo(200);

        // 已销毁证物不能再设置证物级冻结
        assertThat(evidenceHold("bob", uniqueKey("HOLD"), evidenceKey, NOON, "迟来的冻结")
                .getResponse().getStatus()).isEqualTo(409);
        // 已销毁证物再次销毁返回 409
        assertThat(destroy("alice", uniqueKey("DES2"), evidenceKey, "再销毁").getResponse().getStatus())
                .isEqualTo(409);

        // 另起一件证物+冻结，验证重复解除与不存在冻结
        String ev2 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev2, caseKey);
        String holdId = uniqueKey("HOLD");
        assertThat(evidenceHold("bob", holdId, ev2, NOON, "冻结").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(release("carol", uniqueKey("REL"), holdId, "解除").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(release("carol", uniqueKey("REL2"), holdId, "再次解除").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(release("carol", uniqueKey("REL"), uniqueKey("NO-HOLD"), "解除不存在")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void queriesShowTwoLevelHoldsEffectiveStateAndReleaseChain() throws Exception {
        fixClock(BEFORE_NOON);
        String caseKey = uniqueKey("CASE");
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), ev1, caseKey);
        intake("alice", uniqueKey("CMD"), ev2, caseKey);
        String caseHoldId = uniqueKey("HOLD");
        String evHoldId = uniqueKey("HOLD");
        caseHold("bob", caseHoldId, caseKey, NOON, "案件冻结");
        evidenceHold("bob", evHoldId, ev1, NOON.plusHours(2), "证物冻结");

        // 案件视图包含两级冻结
        MvcResult caseViewResult = mockMvc.perform(
                        get("/api/cases/{caseKey}/retention-holds", caseKey)).andReturn();
        assertThat(caseViewResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode caseView = objectMapper.readTree(caseViewResult.getResponse().getContentAsString());
        assertThat(caseView.get("caseKey").asText()).isEqualTo(caseKey);
        assertThat(caseView.get("holds")).hasSize(2);
        assertThat(caseView.get("holds").findValuesAsText("holdRequestId"))
                .containsExactlyInAnyOrder(caseHoldId, evHoldId);

        // 证物视图仅含证物级冻结；保管链含两级
        MvcResult evHoldsResult = mockMvc.perform(
                get("/api/evidence/{key}/retention-holds", ev1)).andReturn();
        JsonNode evHolds = objectMapper.readTree(evHoldsResult.getResponse().getContentAsString());
        assertThat(evHolds).hasSize(1);
        assertThat(evHolds.get(0).get("holdRequestId").asText()).isEqualTo(evHoldId);

        JsonNode chainOfEv1 = chain(ev1);
        assertThat(chainOfEv1.get("holds")).hasSize(2);
        assertThat(chainOfEv1.get("holds").findValuesAsText("level"))
                .containsExactlyInAnyOrder("CASE", "EVIDENCE");

        // 到期案件冻结解除后：ev1 仍有自身冻结阻断，ev2 已无有效冻结
        release("carol", uniqueKey("REL"), caseHoldId, "结案");
        fixClock(NOON);
        assertThat(destroy("alice", uniqueKey("DES"), ev1, "销毁").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(destroy("alice", uniqueKey("DES"), ev2, "销毁").getResponse().getStatus())
                .isEqualTo(200);

        JsonNode finalChain = chain(ev1);
        JsonNode releasedCaseHold = finalChain.get("holds").get(0);
        // 解除链完整保留
        assertThat(releasedCaseHold.get("releasedBy").asText()).isEqualTo("carol");
        assertThat(releasedCaseHold.get("releaseRequestId").asText()).startsWith("REL-");
        assertThat(releasedCaseHold.get("status").asText()).isEqualTo("RELEASED");
        assertThat(releasedCaseHold.get("effective").asBoolean()).isFalse();
    }
}
