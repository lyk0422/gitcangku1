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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 受监管交接 API 主流程与失败分支测试（真实 H2 事务）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegulatedTransferApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String D1 = "2030-06-01T10:00:00";
    private static final String D2 = "2030-06-01T12:00:00";
    private static final String D3 = "2030-06-01T14:00:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey, String sealNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", sealNo);
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return result;
    }

    private MvcResult initiateRegulated(String actor, String evidenceKey, String requestId,
                                        String toCustodian, String transporterId,
                                        List<String> deadlines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("toCustodian", toCustodian);
        body.put("transporterId", transporterId);
        body.put("checkpoints", deadlines.stream()
                .map(d -> Map.of("deadlineAt", d))
                .toList());
        return mockMvc.perform(post("/api/evidence/{key}/regulated-transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult checkin(String actor, String evidenceKey, String requestId,
                              int checkpointNo, String sealNo, String arrivedAt,
                              long expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("checkpointNo", checkpointNo);
        body.put("sealNo", sealNo);
        body.put("arrivedAt", arrivedAt);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/evidence/{key}/regulated-transfers/checkins", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decideRegulated(String actor, String evidenceKey, String requestId,
                                      long expectedVersion, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(
                        post("/api/evidence/{key}/regulated-transfers/{action}", evidenceKey, action)
                                .header(ACTOR_HEADER, actor)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode listRegulated(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/regulated-transfers", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode custodyChain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void fullRegulatedFlowCompletesAndFreezesChain() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-9");

        MvcResult initiated = initiateRegulated("alice", evidenceKey, uniqueKey("REQ"),
                "bob", "ted", List.of(D1, D2));
        assertThat(initiated.getResponse().getStatus()).isEqualTo(201);
        JsonNode transfer = body(initiated);
        assertThat(transfer.get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(transfer.get("version").asLong()).isEqualTo(0);
        assertThat(transfer.get("checkpointCount").asInt()).isEqualTo(2);
        assertThat(transfer.get("sealNo").asText()).isEqualTo("SEAL-9");
        assertThat(transfer.get("fromCustodian").asText()).isEqualTo("alice");
        assertThat(transfer.get("toCustodian").asText()).isEqualTo("bob");
        assertThat(transfer.get("transporterId").asText()).isEqualTo("ted");
        assertThat(transfer.get("checkpoints")).hasSize(2);
        assertThat(transfer.get("checkpoints").get(0).get("deadlineAt").asText()).isEqualTo(D1);
        assertThat(transfer.get("checkpoints").get(1).get("deadlineAt").asText()).isEqualTo(D2);
        // 发起后证物进入待接收
        assertThat(custodyChain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("TRANSFER_PENDING");

        // P1 正常签到（到达早于截止）
        MvcResult c1 = checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-9",
                "2030-06-01T09:00:00", 0);
        assertThat(c1.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(c1).get("version").asLong()).isEqualTo(1);
        assertThat(body(c1).get("checkins")).hasSize(1);

        // P2 到达时刻等于截止时刻，仍合法
        MvcResult c2 = checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-9", D2, 1);
        assertThat(c2.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterC2 = body(c2);
        assertThat(afterC2.get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(afterC2.get("version").asLong()).isEqualTo(2);
        assertThat(afterC2.get("checkins")).hasSize(2);
        assertThat(afterC2.get("exceptions")).isEmpty();

        // 全部签到完成，收件人接收，保管人原子切换
        MvcResult accepted = decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 2, "accept");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode completed = body(accepted);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("decidedAt").isNull()).isFalse();
        // 接收后检查点名单与封条保持冻结值
        assertThat(completed.get("sealNo").asText()).isEqualTo("SEAL-9");
        assertThat(completed.get("checkpoints")).hasSize(2);

        JsonNode chain = custodyChain(evidenceKey);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");

        JsonNode history = listRegulated(evidenceKey);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(history.get(0).get("checkins")).hasSize(2);
    }
    @Test
    void syntheticSkipThenSealMismatchFreezesAndBlocksAccept() throws Exception {
        // 题干合成场景：P1、P2 两点；直接签到 P2 失败且无签到；
        // P1 成功后 P2 封条错误形成异常，接收必须被拒绝。
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(201);

        // 直接签到 P2：跳点失败，且不产生任何签到记录
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(listRegulated(evidenceKey).get(0).get("checkins")).isEmpty();

        // P1 正常签到
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(200);

        // 签到缺失（P2 未签到）时接收返回 422
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 1, "accept")
                .getResponse().getStatus()).isEqualTo(422);

        // P2 封条错误：记录异常并冻结交接
        MvcResult badSeal = checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-WRONG",
                "2030-06-01T11:00:00", 1);
        assertThat(badSeal.getResponse().getStatus()).isEqualTo(200);
        JsonNode frozen = body(badSeal);
        assertThat(frozen.get("status").asText()).isEqualTo("FROZEN");
        assertThat(frozen.get("exceptions")).hasSize(1);
        assertThat(frozen.get("exceptions").get(0).get("reason").asText())
                .isEqualTo("SEAL_MISMATCH");
        assertThat(frozen.get("exceptions").get(0).get("checkpointNo").asInt()).isEqualTo(2);

        // 冻结后：接收被拒绝，普通新签到也不能继续
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 2, "accept")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1",
                "2030-06-01T11:30:00", 2).getResponse().getStatus()).isEqualTo(409);

        // 原保管人取消并重新发起；已记录的异常与签到保留
        assertThat(decideRegulated("alice", evidenceKey, uniqueKey("REQ"), 2, "cancel")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(custodyChain(evidenceKey).get("evidence").get("status").asText())
                .isEqualTo("SEALED");
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(201);

        JsonNode history = listRegulated(evidenceKey);
        assertThat(history).hasSize(2);
        JsonNode oldChain = history.get(0);
        assertThat(oldChain.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(oldChain.get("checkins")).hasSize(2);
        assertThat(oldChain.get("exceptions")).hasSize(1);
        assertThat(oldChain.get("sealNo").asText()).isEqualTo("SEAL-1");
        // 新交接不改旧链，新链独立从 IN_TRANSIT 开始
        assertThat(history.get(1).get("status").asText()).isEqualTo("IN_TRANSIT");
        assertThat(history.get(1).get("checkins")).isEmpty();
    }

    @Test
    void timeoutCheckinRecordsExceptionAndFreezes() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted", List.of(D1, D2));

        // 到达晚于截止：记录 TIMEOUT 异常并冻结
        MvcResult late = checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1",
                "2030-06-01T10:00:01", 0);
        assertThat(late.getResponse().getStatus()).isEqualTo(200);
        JsonNode frozen = body(late);
        assertThat(frozen.get("status").asText()).isEqualTo("FROZEN");
        assertThat(frozen.get("exceptions")).hasSize(1);
        assertThat(frozen.get("exceptions").get(0).get("reason").asText()).isEqualTo("TIMEOUT");
        assertThat(frozen.get("exceptions").get(0).get("checkpointNo").asInt()).isEqualTo(1);

        // 冻结后接收与新签到均被拒绝
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 1, "accept")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1",
                "2030-06-01T11:00:00", 1).getResponse().getStatus()).isEqualTo(409);
        // 冻结交接可由原保管人取消
        assertThat(decideRegulated("alice", evidenceKey, uniqueKey("REQ"), 1, "cancel")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void arrivalTimeMustNotRegress() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2, D3));
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1",
                "2030-06-01T09:30:00", 0).getResponse().getStatus()).isEqualTo(200);
        // 到达时刻早于上一点：拒绝且不产生签到
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1",
                "2030-06-01T09:00:00", 1).getResponse().getStatus()).isEqualTo(409);
        assertThat(listRegulated(evidenceKey).get(0).get("checkins")).hasSize(1);
        // 与上一点相同时刻合法
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1",
                "2030-06-01T09:30:00", 1).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void initiateValidationAndStateGuards() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");

        // 检查点数量越界：1 个与 9 个均 400
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D1, D1, D1, D1, D1, D1, D1, D3)
        ).getResponse().getStatus()).isEqualTo(400);
        // 截止时刻未严格递增：相等或倒退均 400
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D2, D1)).getResponse().getStatus()).isEqualTo(400);
        // 截止时刻早于当前时刻：400
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of("2000-01-01T00:00:00", D1)).getResponse().getStatus()).isEqualTo(400);
        // 收件人与发起人相同：400
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "alice", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(400);
        // 越权发起人：403
        assertThat(initiateRegulated("mallory", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(403);
        // 证物不存在：404
        assertThat(initiateRegulated("alice", uniqueKey("EV"), uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(404);

        // 成功后同一证物仅一条在途交接：再次发起（受监管或普通）均 409
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(201);
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "carol", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(409);
        Map<String, Object> classicBody = Map.of("commandKey", uniqueKey("CMD"), "toCustodian", "carol");
        MvcResult classic = mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(classicBody)))
                .andReturn();
        assertThat(classic.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void borrowBlockedDuringRegulatedTransfer() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted", List.of(D1, D2));

        Map<String, Object> loanBody = new LinkedHashMap<>();
        loanBody.put("commandKey", uniqueKey("CMD"));
        loanBody.put("loanKey", uniqueKey("LOAN"));
        loanBody.put("borrowerId", "rachel");
        loanBody.put("purpose", "lab");
        loanBody.put("dueAt", D1);
        MvcResult loan = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loanBody)))
                .andReturn();
        assertThat(loan.getResponse().getStatus()).isEqualTo(409);
    }
    @Test
    void roleViolationsReturn403() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted", List.of(D1, D2));

        // 非运送人签到：403
        assertThat(checkin("mallory", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(403);
        // 非收件人接收：403
        assertThat(decideRegulated("mallory", evidenceKey, uniqueKey("REQ"), 0, "accept")
                .getResponse().getStatus()).isEqualTo(403);
        // 非原保管人取消（含收件人）：403
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 0, "cancel")
                .getResponse().getStatus()).isEqualTo(403);
        // 越权请求不产生任何签到或状态变化
        JsonNode transfer = listRegulated(evidenceKey).get(0);
        assertThat(transfer.get("checkins")).isEmpty();
        assertThat(transfer.get("status").asText()).isEqualTo("IN_TRANSIT");
    }

    @Test
    void permissionCheckedBeforeReplayAndFailureDoesNotConsumeKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");

        // 越权发起使用 requestId R 返回 403；失败不占键，保管人随后用同键同参成功发起
        String requestId = uniqueKey("REQ");
        assertThat(initiateRegulated("mallory", evidenceKey, requestId, "bob", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(403);
        MvcResult initiated = initiateRegulated("alice", evidenceKey, requestId, "bob", "ted",
                List.of(D1, D2));
        assertThat(initiated.getResponse().getStatus()).isEqualTo(201);

        // 运送人签到成功后，越权者用同键同参重放：返回 403 而非首次响应（权限先于重放）
        String checkinKey = uniqueKey("REQ");
        assertThat(checkin("ted", evidenceKey, checkinKey, 1, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(200);
        assertThat(checkin("mallory", evidenceKey, checkinKey, 1, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void idempotentReplayAndConflictOnChangedParams() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");

        // 同键同参重放：返回首次完整响应，且不产生重复交接
        String initiateKey = uniqueKey("REQ");
        MvcResult first = initiateRegulated("alice", evidenceKey, initiateKey, "bob", "ted",
                List.of(D1, D2));
        MvcResult replay = initiateRegulated("alice", evidenceKey, initiateKey, "bob", "ted",
                List.of(D1, D2));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(listRegulated(evidenceKey)).hasSize(1);

        // 同键改参：409
        assertThat(initiateRegulated("alice", evidenceKey, initiateKey, "carol", "ted",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(409);

        // 签到同键同参重放一致；同键改参 409
        String checkinKey = uniqueKey("REQ");
        MvcResult c1 = checkin("ted", evidenceKey, checkinKey, 1, "SEAL-1",
                "2030-06-01T09:00:00", 0);
        MvcResult c1Replay = checkin("ted", evidenceKey, checkinKey, 1, "SEAL-1",
                "2030-06-01T09:00:00", 0);
        assertThat(c1.getResponse().getStatus()).isEqualTo(200);
        assertThat(c1Replay.getResponse().getContentAsString())
                .isEqualTo(c1.getResponse().getContentAsString());
        assertThat(checkin("ted", evidenceKey, checkinKey, 1, "SEAL-1",
                "2030-06-01T09:05:00", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(listRegulated(evidenceKey).get(0).get("checkins")).hasSize(1);

        // 接收同键重放：保管人只切换一次
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1", D2, 1)
                .getResponse().getStatus()).isEqualTo(200);
        String acceptKey = uniqueKey("REQ");
        MvcResult a1 = decideRegulated("bob", evidenceKey, acceptKey, 2, "accept");
        MvcResult a2 = decideRegulated("bob", evidenceKey, acceptKey, 2, "accept");
        assertThat(a1.getResponse().getStatus()).isEqualTo(200);
        assertThat(a2.getResponse().getStatus()).isEqualTo(200);
        assertThat(a2.getResponse().getContentAsString())
                .isEqualTo(a1.getResponse().getContentAsString());
        assertThat(custodyChain(evidenceKey).get("evidence").get("custodianId").asText())
                .isEqualTo("bob");
    }

    @Test
    void failedBusinessAttemptDoesNotConsumeRequestId() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted", List.of(D1, D2));

        // 跳点失败（409）不占键：同键改正参数后成功
        String requestId = uniqueKey("REQ");
        assertThat(checkin("ted", evidenceKey, requestId, 2, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(409);
        assertThat(checkin("ted", evidenceKey, requestId, 1, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(200);
        assertThat(listRegulated(evidenceKey).get(0).get("checkins")).hasSize(1);
    }

    @Test
    void expectedVersionMustMatchCurrentVersion() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted", List.of(D1, D2));

        // 签到版本不匹配：409
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1",
                "2030-06-01T09:00:00", 5).getResponse().getStatus()).isEqualTo(409);
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1",
                "2030-06-01T09:00:00", 0).getResponse().getStatus()).isEqualTo(200);
        // 取消携带过期版本：409；携带当前版本成功
        assertThat(decideRegulated("alice", evidenceKey, uniqueKey("REQ"), 0, "cancel")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideRegulated("alice", evidenceKey, uniqueKey("REQ"), 1, "cancel")
                .getResponse().getStatus()).isEqualTo(200);
        // 已取消交接不能接收也不能再签到
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 2, "accept")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1", D2, 2)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void acceptRequiresAllCheckpointsCompleted() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey, "SEAL-1");
        initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob", "ted",
                List.of(D1, D2, D3));
        // 未签到任何点：接收 422
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 0, "accept")
                .getResponse().getStatus()).isEqualTo(422);
        checkin("ted", evidenceKey, uniqueKey("REQ"), 1, "SEAL-1", "2030-06-01T09:00:00", 0);
        checkin("ted", evidenceKey, uniqueKey("REQ"), 2, "SEAL-1", "2030-06-01T10:00:00", 1);
        // 仍缺 P3：422
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 2, "accept")
                .getResponse().getStatus()).isEqualTo(422);
        checkin("ted", evidenceKey, uniqueKey("REQ"), 3, "SEAL-1", "2030-06-01T11:00:00", 2);
        assertThat(decideRegulated("bob", evidenceKey, uniqueKey("REQ"), 3, "accept")
                .getResponse().getStatus()).isEqualTo(200);
    }
}