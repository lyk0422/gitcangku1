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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 保留冻结与销毁申请 API 测试：两级冻结阻断、UTC 左闭右开窗口、解除链、
 * 幂等重放/改参、拒绝留档与查询展示。时间全部由固定时钟驱动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetentionHoldApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 合成验收基准日：冻结至 12:00Z，销毁分别在 11:59Z 与 12:00Z 申请。 */
    private static final Instant T_11_00 = Instant.parse("2026-09-30T11:00:00Z");
    private static final Instant T_11_59 = Instant.parse("2026-09-30T11:59:00Z");
    private static final Instant T_12_00 = Instant.parse("2026-09-30T12:00:00Z");
    private static final LocalDateTime EXPIRE_12 = LocalDateTime.parse("2026-09-30T12:00:00");

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

    private void fixClock(Instant instant) {
        evidenceClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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

    private MvcResult holdCase(String actor, String caseKey, String commandKey, String holdKey,
                               LocalDateTime expireAt, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("holdKey", holdKey);
        body.put("expireAt", expireAt == null ? null : expireAt.toString());
        body.put("reason", reason);
        return mockMvc.perform(post("/api/cases/{caseKey}/holds", caseKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult holdEvidence(String actor, String evidenceKey, String commandKey, String holdKey,
                                   LocalDateTime expireAt, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("holdKey", holdKey);
        body.put("expireAt", expireAt == null ? null : expireAt.toString());
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/{evidenceKey}/holds", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult release(String actor, String holdKey, String commandKey, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/holds/{holdKey}/release", holdKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult destroy(String actor, String evidenceKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("reason", "保存期满销毁");
        return mockMvc.perform(post("/api/evidence/{evidenceKey}/destructions", evidenceKey)
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

    private JsonNode holds(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/holds", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode destructions(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/destructions", evidenceKey))
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

    @Test
    void caseKHoldUntilNoonBlocksAt1159AndAllowsAt1200() throws Exception {
        // 合成验收：案件 K 冻结至 12:00Z；所属证物 A 在 11:59Z 申请销毁返回 409；
        // 12:00Z 恰好到期、无其他冻结时可重新申请并销毁。
        fixClock(T_11_00);
        String caseKey = uniqueKey("CASE-K");
        String evidenceKey = uniqueKey("EV-A");
        assertThat(intake("alice", uniqueKey("CMD"), evidenceKey, caseKey).getResponse().getStatus())
                .isEqualTo(201);
        MvcResult held = holdCase("alice", caseKey, uniqueKey("CMD"), uniqueKey("HOLD"),
                EXPIRE_12, "诉讼保留至午时");
        assertThat(held.getResponse().getStatus()).isEqualTo(200);
        JsonNode hold = objectMapper.readTree(held.getResponse().getContentAsString());
        assertThat(hold.get("scope").asText()).isEqualTo("CASE");
        assertThat(hold.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(hold.get("active").asBoolean()).isTrue();

        // 11:59：冻结窗口 [11:00, 12:00) 内，销毁被阻断
        fixClock(T_11_59);
        MvcResult rejected = destroy("alice", evidenceKey, uniqueKey("CMD"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        JsonNode rejection = objectMapper.readTree(rejected.getResponse().getContentAsString());
        assertThat(rejection.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejection.get("blockingHoldKey").asText()).isEqualTo(hold.get("holdKey").asText());
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 恰好 12:00：左闭右开，到期点不再阻断；被拒记录保留且不改写
        fixClock(T_12_00);
        JsonNode holdsView = holds(evidenceKey);
        assertThat(holdsView.get("caseHolds").get(0).get("state").asText()).isEqualTo("EXPIRED");
        assertThat(holdsView.get("caseHolds").get(0).get("active").asBoolean()).isFalse();
        MvcResult approved = destroy("alice", evidenceKey, uniqueKey("CMD"));
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(approved.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("APPROVED");
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("DESTROYED");

        JsonNode history = destructions(evidenceKey);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(history.get(1).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void evidenceHoldBlocksDestructionAndExpiryReopens() throws Exception {
        fixClock(T_11_00);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, uniqueKey("CASE"));
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("HOLD"),
                EXPIRE_12, "单件复核冻结").getResponse().getStatus()).isEqualTo(200);

        fixClock(T_11_59);
        assertThat(destroy("alice", evidenceKey, uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");

        fixClock(T_12_00);
        assertThat(destroy("alice", evidenceKey, uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("DESTROYED");
    }

    @Test
    void bothCaseAndEvidenceHoldsAreChecked() throws Exception {
        fixClock(T_11_00);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);
        // 证物级 11:30 到期，案件级 12:00 到期
        String evHold = uniqueKey("HOLD-EV");
        String caseHold = uniqueKey("HOLD-CASE");
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), evHold,
                LocalDateTime.parse("2026-09-30T11:30:00"), "证物短期冻结").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(holdCase("alice", caseKey, uniqueKey("CMD"), caseHold, EXPIRE_12,
                "案件长期冻结").getResponse().getStatus()).isEqualTo(200);

        // 11:59：证物级已到期，案件级仍生效 → 被案件级阻断
        fixClock(T_11_59);
        MvcResult byCase = destroy("alice", evidenceKey, uniqueKey("CMD"));
        assertThat(byCase.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(byCase.getResponse().getContentAsString())
                .get("blockingHoldKey").asText()).isEqualTo(caseHold);

        // 证物级仍生效时优先被证物级阻断（新证物，案件级到期到 13:00 不影响证物级判定顺序）
        String ev2 = uniqueKey("EV2");
        intake("alice", uniqueKey("CMD"), ev2, caseKey);
        String ev2Hold = uniqueKey("HOLD-EV2");
        assertThat(holdEvidence("alice", ev2, uniqueKey("CMD"), ev2Hold,
                LocalDateTime.parse("2026-09-30T13:00:00"), "证物长期冻结").getResponse().getStatus())
                .isEqualTo(200);
        MvcResult byEvidence = destroy("alice", ev2, uniqueKey("CMD"));
        assertThat(byEvidence.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(byEvidence.getResponse().getContentAsString())
                .get("blockingHoldKey").asText()).isEqualTo(ev2Hold);
    }

    @Test
    void releaseMustBeDifferentCustodianWithReasonAndOnlyAffectsLaterRequests() throws Exception {
        fixClock(T_11_00);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, uniqueKey("CASE"));
        String holdKey = uniqueKey("HOLD");
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), holdKey,
                LocalDateTime.parse("2026-09-30T18:00:00"), "待鉴定冻结").getResponse().getStatus())
                .isEqualTo(200);

        // 冻结期内首次销毁被拒并留档
        MvcResult rejected = destroy("alice", evidenceKey, uniqueKey("CMD"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);

        // 创建人不能自行解除
        assertThat(release("alice", holdKey, uniqueKey("CMD"), "自己解除").getResponse().getStatus())
                .isEqualTo(409);
        // 非保管人不能解除
        assertThat(release("bob", holdKey, uniqueKey("CMD"), "外人解除").getResponse().getStatus())
                .isEqualTo(409);

        // 交接给 bob 后，由不同于创建人的现任保管人 bob 解除
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept("bob", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        MvcResult released = release("bob", holdKey, uniqueKey("CMD"), "鉴定完毕解除保留");
        assertThat(released.getResponse().getStatus()).isEqualTo(200);
        JsonNode releasedHold = objectMapper.readTree(released.getResponse().getContentAsString());
        assertThat(releasedHold.get("state").asText()).isEqualTo("RELEASED");
        assertThat(releasedHold.get("releasedBy").asText()).isEqualTo("bob");
        assertThat(releasedHold.get("releaseReason").asText()).isEqualTo("鉴定完毕解除保留");

        // 重复解除返回 409，解除链不被覆盖
        assertThat(release("bob", holdKey, uniqueKey("CMD"), "再次解除").getResponse().getStatus())
                .isEqualTo(409);

        // 解除只影响后续申请：bob 再次销毁成功；此前 REJECTED 记录原样保留
        MvcResult approved = destroy("bob", evidenceKey, uniqueKey("CMD"));
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        JsonNode history = destructions(evidenceKey);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(history.get(0).get("rejectReason").asText()).contains(holdKey);
        assertThat(history.get(1).get("status").asText()).isEqualTo("APPROVED");
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidenceHolds").get(0).get("state").asText()).isEqualTo("RELEASED");
        assertThat(chain.get("destructions")).hasSize(2);
    }

    @Test
    void holdCreateReplayReturnsFirstResultAndChangedTargetOrExpiryReturns409() throws Exception {
        fixClock(T_11_00);
        String ev1 = uniqueKey("EV1");
        String ev2 = uniqueKey("EV2");
        intake("alice", uniqueKey("CMD"), ev1, uniqueKey("CASE"));
        intake("alice", uniqueKey("CMD"), ev2, uniqueKey("CASE"));

        // 同 requestId 同参重放：返回首次结果，不重复建冻结
        String commandKey = uniqueKey("CMD");
        String holdKey = uniqueKey("HOLD");
        MvcResult first = holdEvidence("alice", ev1, commandKey, holdKey,
                LocalDateTime.parse("2026-09-30T18:00:00"), "首次冻结");
        MvcResult replay = holdEvidence("alice", ev1, commandKey, holdKey,
                LocalDateTime.parse("2026-09-30T18:00:00"), "首次冻结");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(holds(ev1).get("evidenceHolds")).hasSize(1);

        // 换到期时间：409
        assertThat(holdEvidence("alice", ev1, commandKey, holdKey,
                LocalDateTime.parse("2026-09-30T20:00:00"), "首次冻结").getResponse().getStatus())
                .isEqualTo(409);
        // 换对象（另一证物）：409
        assertThat(holdEvidence("alice", ev2, commandKey, holdKey,
                LocalDateTime.parse("2026-09-30T18:00:00"), "首次冻结").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(holds(ev2).get("evidenceHolds")).isEmpty();
    }

    @Test
    void destructionReplayReturnsFirst409AndKeepsSingleRejectedRecord() throws Exception {
        fixClock(T_11_00);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, uniqueKey("CASE"));
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("HOLD"),
                LocalDateTime.parse("2026-09-30T18:00:00"), "冻结").getResponse().getStatus()).isEqualTo(200);

        String commandKey = uniqueKey("CMD");
        MvcResult first = destroy("alice", evidenceKey, commandKey);
        MvcResult replay = destroy("alice", evidenceKey, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不追加第二条申请记录，证物状态不变
        assertThat(destructions(evidenceKey)).hasSize(1);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void invalidExpireAtOrMissingReasonReturns400() throws Exception {
        fixClock(T_12_00);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, uniqueKey("CASE"));

        // 到期时刻不晚于当前：400
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("HOLD"),
                EXPIRE_12, "到期时刻为当前").getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("holdKey", uniqueKey("HOLD"));
        body.put("expireAt", "2026-09-30T18:00:00");
        MvcResult missingReason = mockMvc.perform(post("/api/evidence/{key}/holds", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(missingReason.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void releaseOnExpiredOrMissingHoldReturns409Or404() throws Exception {
        fixClock(T_11_00);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, uniqueKey("CASE"));
        String holdKey = uniqueKey("HOLD");
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), holdKey, EXPIRE_12,
                "短期冻结").getResponse().getStatus()).isEqualTo(200);

        fixClock(T_12_00);
        assertThat(release("alice", holdKey, uniqueKey("CMD"), "到期后解除").getResponse().getStatus())
                .isEqualTo(409);
        // 注意：创建人本来也不能解除；这里用 bob 且先转交保管权，纯粹验证“已到期”分支
        fixClock(T_11_00);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(accept("bob", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        fixClock(T_12_00);
        assertThat(release("bob", holdKey, uniqueKey("CMD"), "到期后解除").getResponse().getStatus())
                .isEqualTo(409);

        assertThat(release("bob", uniqueKey("HOLD-MISSING"), uniqueKey("CMD"), "解除不存在")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void destructionGuardsCustodianAndStateBoundaries() throws Exception {
        fixClock(T_11_00);
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, uniqueKey("CASE"));

        // 非保管人申请销毁：409
        assertThat(destroy("mallory", evidenceKey, uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(409);

        // 正常销毁后再次申请：409；已销毁证物不能再建冻结
        assertThat(destroy("alice", evidenceKey, uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(200);
        assertThat(destroy("alice", evidenceKey, uniqueKey("CMD")).getResponse().getStatus()).isEqualTo(409);
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("HOLD"),
                LocalDateTime.parse("2026-09-30T18:00:00"), "销毁后冻结").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void caseHoldOnUnknownCaseReturns404() throws Exception {
        fixClock(T_11_00);
        assertThat(holdCase("alice", uniqueKey("CASE-NOPE"), uniqueKey("CMD"), uniqueKey("HOLD"),
                LocalDateTime.parse("2026-09-30T18:00:00"), "空案件冻结").getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void holdsQueryShowsTwoLevelsWithExpiryAndReleaseChain() throws Exception {
        fixClock(T_11_00);
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", uniqueKey("CMD"), evidenceKey, caseKey);
        String caseHoldKey = uniqueKey("HOLD-CASE");
        String evHoldKey = uniqueKey("HOLD-EV");
        holdCase("alice", caseKey, uniqueKey("CMD"), caseHoldKey, EXPIRE_12, "案件冻结");
        holdEvidence("alice", evidenceKey, uniqueKey("CMD"), evHoldKey,
                LocalDateTime.parse("2026-09-30T18:00:00"), "证物冻结");

        JsonNode active = holds(evidenceKey);
        assertThat(active.get("caseKey").asText()).isEqualTo(caseKey);
        assertThat(active.get("caseHolds")).hasSize(1);
        assertThat(active.get("evidenceHolds")).hasSize(1);
        assertThat(active.get("caseHolds").get(0).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(active.get("evidenceHolds").get(0).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(active.get("caseHolds").get(0).get("expireAt").asText())
                .isEqualTo("2026-09-30T12:00:00");
        assertThat(active.get("caseHolds").get(0).get("evidenceKey").isNull()).isTrue();

        // 12:00 后案件级 EXPIRED，证物级仍 ACTIVE
        fixClock(T_12_00);
        JsonNode later = holds(evidenceKey);
        assertThat(later.get("caseHolds").get(0).get("state").asText()).isEqualTo("EXPIRED");
        assertThat(later.get("evidenceHolds").get(0).get("state").asText()).isEqualTo("ACTIVE");
    }
}
