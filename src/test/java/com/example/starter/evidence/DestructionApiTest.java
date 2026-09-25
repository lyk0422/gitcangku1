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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 销毁申请双向门禁 API 测试：提交校验（最终状态/封签/有效冻结）、422 稳定列出 holdKey、
 * 冻结生效后待审申请转 HOLD_BLOCKED 且原因不可变、解除后不自动批准须重新提交、
 * 完成销毁后保管链只读、幂等与失败不占键（固定时钟驱动，真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DestructionApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 测试基准时刻（UTC）。 */
    private static final Instant BASE = Instant.parse("2026-09-23T08:00:00Z");

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

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
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

    private MvcResult createHold(String actor, String holdKey, List<String> evidenceKeys,
                                 LocalDateTime from, LocalDateTime to) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdKey", holdKey);
        body.put("caseKey", "CASE-9");
        body.put("evidenceKeys", evidenceKeys);
        body.put("effectiveFrom", from.toString());
        body.put("effectiveTo", to.toString());
        body.put("reason", "案件保全");
        return mockMvc.perform(post("/api/evidence/holds")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult release(String actor, String holdKey, int version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("releases", List.of(Map.of("holdKey", holdKey, "version", version)));
        return mockMvc.perform(post("/api/evidence/holds/release")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult submit(String actor, String requestKey, List<String> evidenceKeys,
                             String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestKey", requestKey);
        body.put("evidenceKeys", evidenceKeys);
        body.put("reason", reason);
        return mockMvc.perform(post("/api/evidence/destruction-requests")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult complete(String actor, String requestKey, String commandKey)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/evidence/destruction-requests/{key}/complete", requestKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String loanKey,
                             LocalDateTime dueAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", loanKey);
        body.put("borrowerId", "bob");
        body.put("purpose", "鉴定用");
        body.put("dueAt", dueAt.toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
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

    private JsonNode destructionRequests(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/{key}/destruction-requests", evidenceKey))
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
    void submitAndCompleteDestroysEvidenceAndKeepsChainReadable() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        inspect("alice", evidenceKey, true);
        String requestKey = uniqueKey("REQ");

        MvcResult submitted = submit("alice", requestKey, List.of(evidenceKey), "案件结案销毁");
        assertThat(submitted.getResponse().getStatus()).isEqualTo(201);
        JsonNode request = objectMapper.readTree(submitted.getResponse().getContentAsString());
        assertThat(request.get("requestKey").asText()).isEqualTo(requestKey);
        assertThat(request.get("status").asText()).isEqualTo("PENDING");
        assertThat(request.get("blockReason").isNull()).isTrue();
        assertThat(request.get("blockedHoldKeys").isNull()).isTrue();

        MvcResult completed = complete("alice", requestKey, uniqueKey("CMD"));
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        JsonNode done = objectMapper.readTree(completed.getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("decidedAt").isNull()).isFalse();

        // 证物进入 DESTROYED 终态；保管链历史（含销毁前核验记录）保持可读不可改写
        JsonNode view = chain(evidenceKey);
        assertThat(view.get("evidence").get("status").asText()).isEqualTo("DESTROYED");
        assertThat(view.get("inspections")).hasSize(1);
        JsonNode requests = destructionRequests(evidenceKey);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void submitBlockedByEffectiveHoldReturns422WithStableHoldKeys() throws Exception {
        fixClock(BASE);
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        createHold("alice", hold1, List.of(ev1), utc(BASE), utc(BASE.plusSeconds(3600)));
        createHold("alice", hold2, List.of(ev2), utc(BASE), utc(BASE.plusSeconds(3600)));

        MvcResult result = submit("alice", uniqueKey("REQ"), List.of(ev1, ev2), "销毁");
        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> holdKeys = objectMapper.convertValue(error.get("holdKeys"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(holdKeys).containsExactlyElementsOf(
                List.of(hold1, hold2).stream().sorted().toList());

        // 不生成部分销毁申请：两件证物均无申请记录
        assertThat(destructionRequests(ev1)).isEmpty();
        assertThat(destructionRequests(ev2)).isEmpty();
    }

    @Test
    void submitValidatesFinalStatusAndSeal() throws Exception {
        fixClock(BASE);
        String borrowed = uniqueKey("EV");
        String pending = uniqueKey("EV");
        String broken = uniqueKey("EV");
        intake("alice", borrowed);
        intake("alice", pending);
        intake("alice", broken);
        borrow("alice", borrowed, uniqueKey("LOAN"), utc(BASE.plusSeconds(3600)));
        initiate("alice", pending, "carol");
        inspect("alice", broken, false);

        assertThat(submit("alice", uniqueKey("REQ"), List.of(borrowed), "销毁")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(submit("alice", uniqueKey("REQ"), List.of(pending), "销毁")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(submit("alice", uniqueKey("REQ"), List.of(broken), "销毁")
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(submit("alice", uniqueKey("REQ"), List.of(uniqueKey("MISSING")), "销毁")
                .getResponse().getStatus()).isEqualTo(404);

        assertThat(destructionRequests(borrowed)).isEmpty();
        assertThat(destructionRequests(pending)).isEmpty();
        assertThat(destructionRequests(broken)).isEmpty();
    }

    @Test
    void pendingRequestTurnsHoldBlockedWhenHoldStarts() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String requestKey = uniqueKey("REQ");
        String holdKey = uniqueKey("HOLD");
        assertThat(submit("alice", requestKey, List.of(evidenceKey), "销毁")
                .getResponse().getStatus()).isEqualTo(201);
        // 冻结在未来生效：创建时立即扫描，申请仍为待审
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        assertThat(destructionRequests(evidenceKey).get(0).get("status").asText())
                .isEqualTo("PENDING");

        // 冻结开始后：待审申请转为 HOLD_BLOCKED，留下不可变原因与冻结快照
        fixClock(BASE.plusSeconds(3600));
        JsonNode blocked = destructionRequests(evidenceKey);
        assertThat(blocked).hasSize(1);
        assertThat(blocked.get(0).get("status").asText()).isEqualTo("HOLD_BLOCKED");
        assertThat(blocked.get(0).get("blockReason").asText()).contains(holdKey);
        assertThat(blocked.get(0).get("blockedHoldKeys").get(0).asText()).isEqualTo(holdKey);
        assertThat(blocked.get(0).get("decidedAt").isNull()).isFalse();
    }

    @Test
    void blockReasonIsImmutableOnceWritten() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String requestKey = uniqueKey("REQ");
        String hold1 = uniqueKey("HOLD");
        String hold2 = uniqueKey("HOLD");
        submit("alice", requestKey, List.of(evidenceKey), "销毁");
        createHold("alice", hold1, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)));
        // 第一笔冻结生效，申请已被阻断
        JsonNode blocked = destructionRequests(evidenceKey);
        String firstReason = blocked.get(0).get("blockReason").asText();
        assertThat(firstReason).contains(hold1);

        // 第二笔不相交冻结随后生效：阻断原因与快照不被改写
        createHold("alice", hold2, List.of(evidenceKey),
                utc(BASE.plusSeconds(3600)), utc(BASE.plusSeconds(7200)));
        fixClock(BASE.plusSeconds(3600));
        JsonNode stillBlocked = destructionRequests(evidenceKey);
        assertThat(stillBlocked.get(0).get("status").asText()).isEqualTo("HOLD_BLOCKED");
        assertThat(stillBlocked.get(0).get("blockReason").asText()).isEqualTo(firstReason);
        assertThat(stillBlocked.get(0).get("blockedHoldKeys")).hasSize(1);
        assertThat(stillBlocked.get(0).get("blockedHoldKeys").get(0).asText()).isEqualTo(hold1);
    }

    @Test
    void blockedRequestNotAutoApprovedAfterReleaseAndMustResubmit() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestKey = uniqueKey("REQ");
        String holdKey = uniqueKey("HOLD");
        submit("alice", requestKey, List.of(evidenceKey), "销毁");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)));
        assertThat(destructionRequests(evidenceKey).get(0).get("status").asText())
                .isEqualTo("HOLD_BLOCKED");

        // 解除冻结：被阻断申请不自动恢复
        assertThat(release("alice", holdKey, 1).getResponse().getStatus()).isEqualTo(200);
        JsonNode afterRelease = destructionRequests(evidenceKey);
        assertThat(afterRelease.get(0).get("status").asText()).isEqualTo("HOLD_BLOCKED");
        // 被阻断申请不能完成
        assertThat(complete("alice", requestKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);

        // 须重新提交新申请；新申请可完成销毁
        String newRequestKey = uniqueKey("REQ");
        assertThat(submit("alice", newRequestKey, List.of(evidenceKey), "解除后重新提交")
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(complete("alice", newRequestKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("DESTROYED");
        // 历史快照：两条申请均保留，阻断记录不可改写
        JsonNode history = destructionRequests(evidenceKey);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("status").asText()).isEqualTo("HOLD_BLOCKED");
        assertThat(history.get(0).get("blockedHoldKeys").get(0).asText()).isEqualTo(holdKey);
        assertThat(history.get(1).get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void expiredHoldDoesNotBlockNewSubmit() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        fixClock(BASE);
        String holdKey = uniqueKey("HOLD");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)));

        // 冻结结束后（区间已过，右开）：新申请不再被阻断
        fixClock(BASE.plusSeconds(3600));
        assertThat(submit("alice", uniqueKey("REQ"), List.of(evidenceKey), "销毁")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void completeOnlyByRequesterAndOnlyWhenPending() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestKey = uniqueKey("REQ");
        submit("alice", requestKey, List.of(evidenceKey), "销毁");

        // 非申请操作人不能完成
        assertThat(complete("mallory", requestKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
        // 不存在的申请 404
        assertThat(complete("alice", uniqueKey("MISSING"), uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(404);

        assertThat(complete("alice", requestKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(200);
        // 已完成申请不能再次完成（新命令）
        assertThat(complete("alice", requestKey, uniqueKey("CMD"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void destroyedEvidenceRejectsAllFurtherOperations() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestKey = uniqueKey("REQ");
        submit("alice", requestKey, List.of(evidenceKey), "销毁");
        complete("alice", requestKey, uniqueKey("CMD"));
        assertThat(chain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("DESTROYED");

        // 终态：借出/交接/核验/再冻结/再销毁申请均被拒绝
        assertThat(borrow("alice", evidenceKey, uniqueKey("LOAN"), utc(BASE.plusSeconds(3600)))
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(initiate("alice", evidenceKey, "carol").getResponse().getStatus())
                .isEqualTo(422);
        assertThat(inspect("alice", evidenceKey, true).getResponse().getStatus()).isEqualTo(422);
        assertThat(createHold("alice", uniqueKey("HOLD"), List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600))).getResponse().getStatus()).isEqualTo(422);
        assertThat(submit("alice", uniqueKey("REQ"), List.of(evidenceKey), "再销毁")
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void submitIdempotentReplayAndKeyConflict() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestKey = uniqueKey("REQ");

        MvcResult first = submit("alice", requestKey, List.of(evidenceKey), "销毁");
        MvcResult replay = submit("alice", requestKey, List.of(evidenceKey), "销毁");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(destructionRequests(evidenceKey)).hasSize(1);

        // 同键改参返回 409
        assertThat(submit("alice", requestKey, List.of(evidenceKey), "改了原因")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(destructionRequests(evidenceKey)).hasSize(1);
    }

    @Test
    void failedSubmitDoesNotOccupyRequestKey() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestKey = uniqueKey("REQ");
        String holdKey = uniqueKey("HOLD");
        createHold("alice", holdKey, List.of(evidenceKey),
                utc(BASE), utc(BASE.plusSeconds(3600)));

        // 命中冻结 422，不占键
        assertThat(submit("alice", requestKey, List.of(evidenceKey), "销毁")
                .getResponse().getStatus()).isEqualTo(422);
        // 解除后同键重试成功
        assertThat(release("alice", holdKey, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(submit("alice", requestKey, List.of(evidenceKey), "销毁")
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void completeIdempotentReplayReturnsFirstResult() throws Exception {
        fixClock(BASE);
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestKey = uniqueKey("REQ");
        String commandKey = uniqueKey("CMD");
        submit("alice", requestKey, List.of(evidenceKey), "销毁");

        MvcResult first = complete("alice", requestKey, commandKey);
        MvcResult replay = complete("alice", requestKey, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(destructionRequests(evidenceKey)).hasSize(1);
    }
}
