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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 保全冻结与销毁申请双向门禁：主流程、失败分支、批量回滚与历史快照测试（真实 H2 数据库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RetentionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EvidenceClock evidenceClock;

    private LocalDateTime now;
    private LocalDateTime later;
    private LocalDateTime farFuture;

    @BeforeEach
    void setUp() {
        Instant fixed = Instant.parse("2026-09-26T00:00:00Z");
        evidenceClock.setClock(Clock.fixed(fixed, ZoneOffset.UTC));
        now = LocalDateTime.ofInstant(fixed, ZoneOffset.UTC);
        later = now.plusHours(12);
        farFuture = now.plusDays(30);
    }

    @AfterEach
    void tearDown() {
        evidenceClock.setClock(Clock.systemUTC());
    }

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createHold(String actor, String commandKey, String holdId, String caseKey,
                                 List<String> evidenceKeys, LocalDateTime effectiveAt,
                                 LocalDateTime expireAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("holdId", holdId);
        body.put("caseKey", caseKey);
        body.put("reason", "litigation-hold");
        body.put("effectiveAt", effectiveAt.toString());
        body.put("expireAt", expireAt.toString());
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/evidence/holds")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createHold(String actor, String holdId, List<String> evidenceKeys,
                                 LocalDateTime effectiveAt, LocalDateTime expireAt) throws Exception {
        return createHold(actor, uniqueKey("CMD"), holdId, "CASE-9", evidenceKeys,
                effectiveAt, expireAt);
    }

    private MvcResult batchRelease(String actor, String commandKey,
                                   List<Map<String, Object>> releases) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("releases", releases);
        return mockMvc.perform(post("/api/evidence/holds/batch-release")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private Map<String, Object> releaseItem(String holdId, int version) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("holdId", holdId);
        item.put("expectedVersion", version);
        return item;
    }

    private MvcResult submitDestruction(String actor, String commandKey, String requestKey,
                                        List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("requestKey", requestKey);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/evidence/destruction-requests")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult completeDestruction(String actor, String requestKey, String commandKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/evidence/destruction-requests/{key}/complete", requestKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode effectiveHolds(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/holds/effective", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    private JsonNode getDestruction(String requestKey) throws Exception {
        MvcResult result = mockMvc.perform(
                get("/api/evidence/destruction-requests/{key}", requestKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    @Test
    void createHoldNormalizesSetAndReturnsEffectiveHold() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        String ev3 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        intake("alice", ev3);
        String holdId = uniqueKey("HOLD");

        MvcResult result = createHold("alice", holdId, List.of(ev3, ev1, ev2, ev1), now, farFuture);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.get("holdId").asText()).isEqualTo(holdId);
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("effectiveNow").asBoolean()).isTrue();
        // 集合去重并按字典序规范化
        assertThat(body.get("evidenceKeys")).hasSize(3);
        List<String> keys = objectMapper.convertValue(body.get("evidenceKeys"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        assertThat(keys).isSorted().doesNotHaveDuplicates();

        JsonNode effective = effectiveHolds(ev2);
        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).get("holdId").asText()).isEqualTo(holdId);
    }

    @Test
    void overlappingHoldOnSameEvidenceReturns422WithStableHoldId() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        assertThat(createHold("alice", hold1, List.of(ev), now, later).getResponse().getStatus())
                .isEqualTo(201);

        // 区间重叠 [now+6h, now+18h) vs [now, now+12h)
        MvcResult overlap = createHold("alice", hold2, List.of(ev),
                now.plusHours(6), now.plusHours(18));
        assertThat(overlap.getResponse().getStatus()).isEqualTo(422);
        assertThat(overlap.getResponse().getContentAsString()).contains(hold1);

        // 失败不占键：该 holdId 之后仍可在不重叠区间创建成功
        MvcResult retry = createHold("alice", uniqueKey("CMD"), hold2, "CASE-9",
                List.of(ev), later, farFuture);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void adjacentHalfOpenRangesDoNotOverlap() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        assertThat(createHold("alice", hold1, List.of(ev), now, later).getResponse().getStatus())
                .isEqualTo(201);
        // [now, later) 与 [later, farFuture) 首尾相接：左闭右开，不重叠
        MvcResult adjacent = createHold("alice", hold2, List.of(ev), later, farFuture);
        assertThat(adjacent.getResponse().getStatus()).isEqualTo(201);
        assertThat(effectiveHolds(ev)).hasSize(1);
    }

    @Test
    void backdatedOrInvalidRangeReturns400AndDoesNotOccupyKey() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String commandKey = uniqueKey("CMD");
        String holdId = uniqueKey("HOLD");

        MvcResult backdated = createHold("alice", commandKey, holdId, "CASE-9", List.of(ev),
                now.minusMinutes(1), farFuture);
        assertThat(backdated.getResponse().getStatus()).isEqualTo(400);

        MvcResult badRange = createHold("alice", uniqueKey("CMD"), uniqueKey("HOLD"), "CASE-9",
                List.of(ev), later, now);
        assertThat(badRange.getResponse().getStatus()).isEqualTo(400);

        // 被 400 拒绝的 commandKey/holdId 未占用，修正参数后同键同 holdId 可成功
        MvcResult retry = createHold("alice", commandKey, holdId, "CASE-9", List.of(ev),
                now, farFuture);
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void holdOnMissingEvidenceReturns404AndDuplicateHoldIdReturns409() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        assertThat(createHold("alice", holdId, List.of(ev), now, farFuture).getResponse().getStatus())
                .isEqualTo(201);

        assertThat(createHold("alice", holdId, List.of(ev), now, farFuture).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(uniqueKey("NOPE")),
                now, farFuture).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void holdCreateIdempotentReplayReturnsFirstResult() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String commandKey = uniqueKey("CMD");
        String holdId = uniqueKey("HOLD");
        MvcResult first = createHold("alice", commandKey, holdId, "CASE-1", List.of(ev),
                now, farFuture);
        MvcResult replay = createHold("alice", commandKey, holdId, "CASE-1", List.of(ev),
                now, farFuture);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(effectiveHolds(ev)).hasSize(1);
    }

    @Test
    void sameCommandKeyWithDifferentHoldParamsReturns409() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String commandKey = uniqueKey("CMD");
        assertThat(createHold("alice", commandKey, uniqueKey("HOLD"), "CASE-1", List.of(ev1),
                now, farFuture).getResponse().getStatus()).isEqualTo(201);
        assertThat(createHold("alice", commandKey, uniqueKey("HOLD"), "CASE-2", List.of(ev2),
                now, farFuture).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void destructionSubmitWithoutHoldSucceedsAndIsQueried() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String requestKey = uniqueKey("DREQ");

        MvcResult result = submitDestruction("alice", uniqueKey("CMD"), requestKey, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("evidenceKeys")).hasSize(1);
        assertThat(body.get("blockedHolds")).isEmpty();

        JsonNode view = getDestruction(requestKey);
        assertThat(view.get("requestKey").asText()).isEqualTo(requestKey);
        assertThat(view.get("blockedAt").isNull()).isTrue();
    }

    @Test
    void destructionSubmitBlockedByHoldReturns422AndCreatesNothing() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        createHold("alice", holdId, List.of(ev), now, farFuture);
        String requestKey = uniqueKey("DREQ");

        MvcResult result = submitDestruction("alice", uniqueKey("CMD"), requestKey, List.of(ev));
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentAsString()).contains(holdId);

        // 未生成任何销毁申请
        MvcResult lookup = mockMvc.perform(
                get("/api/evidence/destruction-requests/{key}", requestKey)).andReturn();
        assertThat(lookup.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void destructionSubmitChecksSealStatusCustodianAndBorrowedState() throws Exception {
        String broken = uniqueKey("EV");
        String borrowed = uniqueKey("EV");
        String foreign = uniqueKey("EV");
        intake("alice", broken);
        intake("alice", borrowed);
        intake("alice", foreign);

        Map<String, Object> inspect = new LinkedHashMap<>();
        inspect.put("commandKey", uniqueKey("CMD"));
        inspect.put("passed", false);
        inspect.put("note", "cracked");
        mockMvc.perform(post("/api/evidence/{key}/seal-inspections", broken)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(inspect))).andReturn();

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("commandKey", uniqueKey("CMD"));
        loan.put("loanKey", uniqueKey("LOAN"));
        loan.put("borrowerId", "bob");
        loan.put("purpose", "analysis");
        loan.put("dueAt", now.plusHours(24).toString());
        mockMvc.perform(post("/api/evidence/{key}/loans", borrowed)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loan))).andReturn();

        // 封签异常：422；非保管人：409；借出未归还：409；均不生成申请
        assertThat(submitDestruction("alice", uniqueKey("CMD"), uniqueKey("DREQ"), List.of(broken))
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(submitDestruction("mallory", uniqueKey("CMD"), uniqueKey("DREQ"), List.of(foreign))
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(submitDestruction("alice", uniqueKey("CMD"), uniqueKey("DREQ"), List.of(borrowed))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void pendingDestructionBecomesBlockedWhenHoldStartsAndSnapshotIsImmutable() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String requestKey = uniqueKey("DREQ");
        assertThat(submitDestruction("alice", uniqueKey("CMD"), requestKey, List.of(ev))
                .getResponse().getStatus()).isEqualTo(201);

        // 冻结开始后，待审申请转 HOLD_BLOCKED
        String holdId = uniqueKey("HOLD");
        MvcResult holdResult = createHold("alice", holdId, List.of(ev), now, farFuture);
        assertThat(holdResult.getResponse().getStatus()).isEqualTo(201);

        JsonNode blocked = getDestruction(requestKey);
        assertThat(blocked.get("status").asText()).isEqualTo("HOLD_BLOCKED");
        assertThat(blocked.get("blockedAt").isNull()).isFalse();
        assertThat(blocked.get("blockedHolds")).hasSize(1);
        JsonNode snapshot = blocked.get("blockedHolds").get(0);
        assertThat(snapshot.get("holdId").asText()).isEqualTo(holdId);
        assertThat(snapshot.get("version").asInt()).isEqualTo(1);
        assertThat(snapshot.get("caseKey").asText()).isEqualTo("CASE-9");
        assertThat(snapshot.get("reason").asText()).isEqualTo("litigation-hold");

        // 解除冻结后不自动批准，阻断申请不能完成，须重新提交
        MvcResult release = batchRelease("alice", uniqueKey("CMD"),
                List.of(releaseItem(holdId, 1)));
        assertThat(release.getResponse().getStatus()).isEqualTo(200);

        MvcResult complete = completeDestruction("alice", requestKey, uniqueKey("CMD"));
        assertThat(complete.getResponse().getStatus()).isEqualTo(409);
        assertThat(complete.getResponse().getContentAsString()).contains("重新提交");

        // 原快照不可变：冻结版本已递增为 2，快照仍记录阻断瞬间的版本 1
        JsonNode stillBlocked = getDestruction(requestKey);
        assertThat(stillBlocked.get("status").asText()).isEqualTo("HOLD_BLOCKED");
        assertThat(stillBlocked.get("blockedHolds").get(0).get("version").asInt()).isEqualTo(1);

        // 重新提交后可完成销毁
        String requestKey2 = uniqueKey("DREQ");
        assertThat(submitDestruction("alice", uniqueKey("CMD"), requestKey2, List.of(ev))
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult completed = completeDestruction("alice", requestKey2, uniqueKey("CMD"));
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(completed).get("status").asText()).isEqualTo("DESTROYED");

        JsonNode chain = objectMapper.readTree(mockMvc.perform(
                get("/api/evidence/{key}/custody-chain", ev)).andReturn().getResponse().getContentAsString());
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("DESTROYED");
    }

    @Test
    void expiredHoldDoesNotBlockDestruction() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        // [now-24h, now-1h) 为补建过去，禁止；用未来极短冻结再推移时钟模拟到期
        createHold("alice", holdId, List.of(ev), now, now.plusHours(1));

        evidenceClock.setClock(Clock.fixed(now.plusHours(2).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        assertThat(effectiveHolds(ev)).isEmpty();

        String requestKey = uniqueKey("DREQ");
        assertThat(submitDestruction("alice", uniqueKey("CMD"), requestKey, List.of(ev))
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void futureHoldDoesNotBlockDestructionUntilEffective() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        createHold("alice", holdId, List.of(ev), now.plusHours(6), farFuture);

        String requestKey = uniqueKey("DREQ");
        assertThat(submitDestruction("alice", uniqueKey("CMD"), requestKey, List.of(ev))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(getDestruction(requestKey).get("status").asText()).isEqualTo("PENDING");

        // 冻结生效后首次完成销毁：返回 422，申请被持久化为 HOLD_BLOCKED 并留快照
        evidenceClock.setClock(Clock.fixed(now.plusHours(7).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        MvcResult complete = completeDestruction("alice", requestKey, uniqueKey("CMD"));
        assertThat(complete.getResponse().getStatus()).isEqualTo(422);
        JsonNode blocked = getDestruction(requestKey);
        assertThat(blocked.get("status").asText()).isEqualTo("HOLD_BLOCKED");
        assertThat(blocked.get("blockedHolds")).hasSize(1);
        assertThat(blocked.get("blockedHolds").get(0).get("holdId").asText()).isEqualTo(holdId);

        // 失败不占 commandKey；解除后重新提交方可销毁
        MvcResult release = batchRelease("alice", uniqueKey("CMD"), List.of(releaseItem(holdId, 1)));
        assertThat(release.getResponse().getStatus()).isEqualTo(200);
        assertThat(completeDestruction("alice", requestKey, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(409);
        String requestKey2 = uniqueKey("DREQ");
        assertThat(submitDestruction("alice", uniqueKey("CMD"), requestKey2, List.of(ev))
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(completeDestruction("alice", requestKey2, uniqueKey("CMD")).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void destroyedEvidenceCannotBeHoldedAndHistoryIsQueryable() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String requestKey = uniqueKey("DREQ");
        submitDestruction("alice", uniqueKey("CMD"), requestKey, List.of(ev));
        completeDestruction("alice", requestKey, uniqueKey("CMD"));

        // 已销毁证物不能补建冻结
        MvcResult hold = createHold("alice", uniqueKey("HOLD"), List.of(ev), now, farFuture);
        assertThat(hold.getResponse().getStatus()).isEqualTo(422);

        // 销毁历史可查
        MvcResult history = mockMvc.perform(
                get("/api/evidence/{key}/destruction-requests", ev)).andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        JsonNode list = json(history);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("status").asText()).isEqualTo("DESTROYED");
    }

    @Test
    void batchReleaseSucceedsAtomically() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1), now, farFuture);
        createHold("alice", hold2, List.of(ev2), now, farFuture);

        MvcResult result = batchRelease("alice", uniqueKey("CMD"),
                List.of(releaseItem(hold1, 1), releaseItem(hold2, 1)));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(result).get("released")).hasSize(2);        assertThat(effectiveHolds(ev1)).isEmpty();
        assertThat(effectiveHolds(ev2)).isEmpty();

        // 重复解除返回 409
        MvcResult again = batchRelease("alice", uniqueKey("CMD"),
                List.of(releaseItem(hold1, 2)));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void batchReleaseWrongRequesterRollsBackWholeBatch() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1), now, farFuture);
        createHold("alice", hold2, List.of(ev2), now, farFuture);

        // mallory 不是任一冻结的创建方：整批回滚
        MvcResult result = batchRelease("mallory", uniqueKey("CMD"),
                List.of(releaseItem(hold1, 1), releaseItem(hold2, 1)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("创建请求方");

        assertThat(effectiveHolds(ev1)).hasSize(1);
        assertThat(effectiveHolds(ev2)).hasSize(1);
    }

    @Test
    void batchReleaseVersionMismatchRollsBackWholeBatch() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1), now, farFuture);
        createHold("alice", hold2, List.of(ev2), now, farFuture);

        // 第一项版本正确、第二项版本过期：整批回滚，第一项也不得解除
        MvcResult result = batchRelease("alice", uniqueKey("CMD"),
                List.of(releaseItem(hold1, 1), releaseItem(hold2, 9)));
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("版本不匹配");

        assertThat(effectiveHolds(ev1)).hasSize(1);
        assertThat(effectiveHolds(ev2)).hasSize(1);
    }

    @Test
    void batchReleaseUnknownHoldReturns404AndDuplicateReturns400() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String hold1 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev), now, farFuture);

        assertThat(batchRelease("alice", uniqueKey("CMD"),
                List.of(releaseItem(uniqueKey("MISSING"), 1))).getResponse().getStatus()).isEqualTo(404);

        assertThat(batchRelease("alice", uniqueKey("CMD"),
                List.of(releaseItem(hold1, 1), releaseItem(hold1, 1))).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(effectiveHolds(ev)).hasSize(1);
    }

    @Test
    void effectiveHoldBlocksLoanButReleaseAllowsIt() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        createHold("alice", holdId, List.of(ev), now, farFuture);

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("commandKey", uniqueKey("CMD"));
        loan.put("loanKey", uniqueKey("LOAN"));
        loan.put("borrowerId", "bob");
        loan.put("purpose", "analysis");
        loan.put("dueAt", now.plusHours(24).toString());
        MvcResult blocked = mockMvc.perform(post("/api/evidence/{key}/loans", ev)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loan))).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(422);

        batchRelease("alice", uniqueKey("CMD"), List.of(releaseItem(holdId, 1)));
        MvcResult allowed = mockMvc.perform(post("/api/evidence/{key}/loans", ev)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loan))).andReturn();
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void blockedDestructionFailureDoesNotOccupyCommandKey() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String holdId = uniqueKey("HOLD");
        createHold("alice", holdId, List.of(ev), now, farFuture);

        String commandKey = uniqueKey("CMD");
        String requestKey = uniqueKey("DREQ");
        assertThat(submitDestruction("alice", commandKey, requestKey, List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);

        // 解除后复用同一 commandKey 与 requestKey 提交成功（失败不占键）
        batchRelease("alice", uniqueKey("CMD"), List.of(releaseItem(holdId, 1)));
        MvcResult retry = submitDestruction("alice", commandKey, requestKey, List.of(ev));
        assertThat(retry.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void holdHistoryIncludesReleasedAndFutureHolds() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev), now, later);
        createHold("alice", hold2, List.of(ev), now.plusDays(1), farFuture);

        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/holds/history", ev)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode history = json(result);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("holdId").asText()).isEqualTo(hold1);
        assertThat(history.get(1).get("holdId").asText()).isEqualTo(hold2);
    }
}
