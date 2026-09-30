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
 * 保留冻结与销毁申请的真实 H2（MODE=MySQL）API 测试：
 * 主流程、冻结拒绝分支、左闭右开到期边界、解除链、幂等重放/改参 409、非法输入与历史查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetentionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    @BeforeEach
    void fixClock() {
        evidenceClock.setClock(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private void setClockUtc(String utc) {
        evidenceClock.setClock(Clock.fixed(Instant.parse(utc + "Z"), ZoneOffset.UTC));
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

    private MvcResult holdCase(String actor, String caseKey, String requestId,
                               String expiresUtc, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expiresUtc", expiresUtc);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/cases/{caseKey}/holds", caseKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult holdEvidence(String actor, String evidenceKey, String requestId,
                                   String expiresUtc, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expiresUtc", expiresUtc);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/{evidenceKey}/holds", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult release(String actor, long holdId, String requestId, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/holds/{holdId}/release", holdId)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult destroy(String actor, String evidenceKey, String requestId, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/{evidenceKey}/destructions", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode evidenceHolds(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/holds", evidenceKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode caseHolds(String caseKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/cases/{key}/holds", caseKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode destructions(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/destructions", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode chain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/evidence/{key}/custody-chain", evidenceKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    @Test
    void acceptanceCaseHoldBlocksAt1159AndAllowsAgainAt1200() throws Exception {
        // 合成验收：案件 K 冻结至 12:00，证物 A 在 11:59 申请销毁返回 409；12:00 无其他冻结时可重新申请。
        String caseKey = uniqueKey("CASE-K");
        String evidenceKey = uniqueKey("EV-A");
        assertThat(intake("alice", evidenceKey, caseKey).getResponse().getStatus()).isEqualTo(201);

        MvcResult hold = holdCase("alice", caseKey, uniqueKey("HOLD"),
                "2026-10-01T12:00:00", "案件复查保留");
        assertThat(hold.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(hold).get("scope").asText()).isEqualTo("CASE");
        assertThat(json(hold).get("effective").asBoolean()).isTrue();

        setClockUtc("2026-10-01T11:59:00");
        MvcResult rejectedAt1159 = destroy("alice", evidenceKey, uniqueKey("DEST"), "到期销毁");
        assertThat(rejectedAt1159.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(rejectedAt1159).get("status").asText()).isEqualTo("REJECTED");
        assertThat(json(rejectedAt1159).get("blockingHoldId").isNull()).isFalse();
        // 被拒后原证物状态不变
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");

        // 同一 requestId 即使到 12:00 仍重放首次 409
        String firstRequestId = json(rejectedAt1159).get("requestId").asText();
        setClockUtc("2026-10-01T12:00:00");
        assertThat(destroy("alice", evidenceKey, firstRequestId, "到期销毁").getResponse().getStatus())
                .isEqualTo(409);

        // 恰好到期（12:00，左闭右开）后，用新 requestId 可重新申请并销毁
        MvcResult approvedAt1200 = destroy("alice", evidenceKey, uniqueKey("DEST"), "到期销毁");
        assertThat(approvedAt1200.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(approvedAt1200).get("status").asText()).isEqualTo("APPROVED");
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("DESTROYED");

        // 历史不改写：被拒记录仍为 REJECTED，批准记录为 APPROVED
        JsonNode history = destructions(evidenceKey);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(history.get(1).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void evidenceHoldBlocksDestructionAndExpiryReopens() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));

        MvcResult hold = holdEvidence("alice", evidenceKey, uniqueKey("HOLD"),
                "2026-10-01T11:30:00", "单证物冻结");
        assertThat(hold.getResponse().getStatus()).isEqualTo(201);
        long holdId = json(hold).get("id").asLong();

        setClockUtc("2026-10-01T11:00:00");
        MvcResult rejected = destroy("alice", evidenceKey, uniqueKey("DEST"), "销毁");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(rejected).get("blockingHoldId").asLong()).isEqualTo(holdId);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");

        setClockUtc("2026-10-01T11:30:00");
        MvcResult approved = destroy("alice", evidenceKey, uniqueKey("DEST"), "销毁");
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("DESTROYED");
    }

    @Test
    void releaseByDifferentCustodianOnlyAffectsLaterRequests() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        MvcResult hold = holdEvidence("alice", evidenceKey, uniqueKey("HOLD"),
                "2026-10-01T18:00:00", "诉讼保留");
        long holdId = json(hold).get("id").asLong();

        // 创建人不能解除自己的冻结
        assertThat(release("alice", holdId, uniqueKey("REL"), "自行解除").getResponse().getStatus())
                .isEqualTo(409);

        // 冻结仍有效，销毁被拒
        MvcResult rejected = destroy("alice", evidenceKey, uniqueKey("DEST"), "销毁");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);

        // 另一保管人签明原因解除
        MvcResult released = release("bob", holdId, uniqueKey("REL"), "复查结束解除");
        assertThat(released.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(released).get("status").asText()).isEqualTo("RELEASED");
        assertThat(json(released).get("effective").asBoolean()).isFalse();
        assertThat(json(released).get("releasedBy").asText()).isEqualTo("bob");

        // 解除只影响后续申请：过去被拒记录仍为 REJECTED，新申请可批准
        JsonNode history = destructions(evidenceKey);
        assertThat(history.get(0).get("status").asText()).isEqualTo("REJECTED");
        MvcResult approved = destroy("alice", evidenceKey, uniqueKey("DEST"), "销毁");
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);

        // 已解除冻结不能再次解除
        assertThat(release("carol", holdId, uniqueKey("REL2"), "再次解除").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void holdIdempotentReplayReturnsFirstResult() throws Exception {
        String caseKey = uniqueKey("CASE");
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, caseKey);
        String requestId = uniqueKey("HOLD");

        MvcResult first = holdCase("alice", caseKey, requestId, "2026-10-01T12:00:00", "原因");
        MvcResult replay = holdCase("alice", caseKey, requestId, "2026-10-01T12:00:00", "原因");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同 requestId 换到期时间返回 409
        assertThat(holdCase("alice", caseKey, requestId, "2026-10-01T13:00:00", "原因")
                .getResponse().getStatus()).isEqualTo(409);
        // 同 requestId 换案件返回 409
        String otherCase = uniqueKey("CASE");
        intake("alice", uniqueKey("EV2"), otherCase);
        assertThat(holdCase("alice", otherCase, requestId, "2026-10-01T12:00:00", "原因")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void releaseIdempotentReplayAndParamMismatch() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        long holdId = json(holdEvidence("alice", evidenceKey, uniqueKey("HOLD"),
                "2026-10-01T18:00:00", "保留")).get("id").asLong();
        String releaseId = uniqueKey("REL");

        MvcResult first = release("bob", holdId, releaseId, "解除原因");
        MvcResult replay = release("bob", holdId, releaseId, "解除原因");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同一解除 requestId 换原因返回 409
        assertThat(release("bob", holdId, releaseId, "另一个原因").getResponse().getStatus())
                .isEqualTo(409);

        // 同一解除 requestId 换另一冻结返回 409
        long otherHoldId = json(holdEvidence("alice", evidenceKey, uniqueKey("HOLD2"),
                "2026-10-01T19:00:00", "保留2")).get("id").asLong();
        assertThat(release("bob", otherHoldId, releaseId, "解除原因").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void destructionReplayFirstRejectionEvenAfterExpiry() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        holdEvidence("alice", evidenceKey, uniqueKey("HOLD"), "2026-10-01T11:00:00", "保留");

        setClockUtc("2026-10-01T10:30:00");
        String requestId = uniqueKey("DEST");
        MvcResult rejected = destroy("alice", evidenceKey, requestId, "销毁");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);

        // 同键同参到期后仍重放首次 409，不产生第二条记录
        setClockUtc("2026-10-01T11:30:00");
        MvcResult replay = destroy("alice", evidenceKey, requestId, "销毁");
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(rejected.getResponse().getContentAsString());
        assertThat(destructions(evidenceKey)).hasSize(1);

        // 同键换证物返回 409
        String otherEvidence = uniqueKey("EV2");
        intake("alice", otherEvidence, uniqueKey("CASE"));
        assertThat(destroy("alice", otherEvidence, requestId, "销毁").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void invalidExpiryReturns400AndMissingFieldsReturn400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));

        // 到期时刻不晚于当前时刻
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("HOLD"),
                "2026-10-01T09:00:00", "过去到期").getResponse().getStatus()).isEqualTo(400);
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("HOLD"),
                "2026-10-01T10:00:00", "恰好当前").getResponse().getStatus()).isEqualTo(400);

        // 缺少 requestId
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expiresUtc", "2026-10-01T12:00:00");
        body.put("reason", "x");
        MvcResult missing = mockMvc.perform(post("/api/evidence/{key}/holds", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void holdOnMissingTargetsAndDestroyedEvidenceReturnConflictOrNotFound() throws Exception {
        assertThat(holdCase("alice", uniqueKey("NO-CASE"), uniqueKey("HOLD"),
                "2026-10-01T12:00:00", "x").getResponse().getStatus()).isEqualTo(404);
        assertThat(holdEvidence("alice", uniqueKey("NO-EV"), uniqueKey("HOLD"),
                "2026-10-01T12:00:00", "x").getResponse().getStatus()).isEqualTo(404);

        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));
        assertThat(destroy("alice", evidenceKey, uniqueKey("DEST"), "销毁").getResponse().getStatus())
                .isEqualTo(200);
        // 已销毁证物不能再冻结、再销毁
        assertThat(holdEvidence("alice", evidenceKey, uniqueKey("HOLD"),
                "2026-10-01T12:00:00", "x").getResponse().getStatus()).isEqualTo(409);
        assertThat(destroy("alice", evidenceKey, uniqueKey("DEST2"), "x").getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void queriesShowTwoLevelHoldsEffectiveStateAndReleaseChain() throws Exception {
        String caseKey = uniqueKey("CASE");
        String evA = uniqueKey("EV-A");
        String evB = uniqueKey("EV-B");
        intake("alice", evA, caseKey);
        intake("alice", evB, caseKey);

        long caseHoldId = json(holdCase("alice", caseKey, uniqueKey("HOLD-CASE"),
                "2026-10-01T12:00:00", "案件冻结")).get("id").asLong();
        json(holdEvidence("alice", evA, uniqueKey("HOLD-EV"),
                "2026-10-01T18:00:00", "证物冻结"));
        release("bob", caseHoldId, uniqueKey("REL"), "案件冻结解除");

        // 案件视图同时含两级冻结与解除链
        JsonNode caseView = caseHolds(caseKey);
        assertThat(caseView.get("holds")).hasSize(2);
        JsonNode releasedCaseHold = caseView.get("holds").get(0);
        assertThat(releasedCaseHold.get("scope").asText()).isEqualTo("CASE");
        assertThat(releasedCaseHold.get("status").asText()).isEqualTo("RELEASED");
        assertThat(releasedCaseHold.get("effective").asBoolean()).isFalse();
        assertThat(releasedCaseHold.get("releasedBy").asText()).isEqualTo("bob");
        assertThat(releasedCaseHold.get("releaseReason").asText()).isEqualTo("案件冻结解除");
        JsonNode evidenceHold = caseView.get("holds").get(1);
        assertThat(evidenceHold.get("scope").asText()).isEqualTo("EVIDENCE");
        assertThat(evidenceHold.get("effective").asBoolean()).isTrue();

        // 证物 A 的证物级冻结列表只含 EVIDENCE 级
        assertThat(evidenceHolds(evA)).hasSize(1);
        assertThat(evidenceHolds(evA).get(0).get("scope").asText()).isEqualTo("EVIDENCE");

        // 证物 A 保管链含案件级与证物级两级冻结
        JsonNode chain = chain(evA);
        assertThat(chain.get("holds")).hasSize(2);
        assertThat(chain.get("holds").findValuesAsText("scope"))
                .containsExactlyInAnyOrder("CASE", "EVIDENCE");

        // 案件冻结已解除，仅剩证物级冻结：销毁 A 被证物级冻结阻断；销毁 B 无有效冻结可批准
        assertThat(destroy("alice", evA, uniqueKey("DEST"), "x").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(destroy("alice", evB, uniqueKey("DEST"), "x").getResponse().getStatus())
                .isEqualTo(200);

        // 到期后证物级冻结 effective 转 false（状态仍为 ACTIVE，不回写）
        setClockUtc("2026-10-01T18:00:00");
        JsonNode expiredHold = evidenceHolds(evA).get(0);
        assertThat(expiredHold.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(expiredHold.get("effective").asBoolean()).isFalse();
    }

    @Test
    void holdsBlockWhileBorrowedOrPendingAlsoRespected() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("commandKey", uniqueKey("CMD"));
        transfer.put("toCustodian", "bob");
        mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(transfer))).andReturn();

        assertThat(destroy("alice", evidenceKey, uniqueKey("DEST"), "x").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(destructions(evidenceKey)).isEmpty();
    }

    @Test
    void destructionByNonCustodianReturns409AndStateUnchanged() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, uniqueKey("CASE"));

        MvcResult rejected = destroy("mallory", evidenceKey, uniqueKey("DEST"), "非保管人销毁");
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        // 非保管人不产生销毁记录，证物仍由 alice 保管且为 SEALED
        assertThat(destructions(evidenceKey)).isEmpty();
        assertThat(chain(evidenceKey).get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chain(evidenceKey).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }
}
