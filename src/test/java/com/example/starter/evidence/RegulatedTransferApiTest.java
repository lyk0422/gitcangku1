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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 受监管交接（检查点路由）API 测试：主流程、参数校验、越权 403、
 * 顺序/时间/封条规则、异常冻结、取消重发与幂等边界（真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegulatedTransferApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String SEAL = "SEAL-1";
    private static final String D1 = "2026-10-06T10:00:00";
    private static final String D2 = "2026-10-06T12:00:00";
    private static final String D3 = "2026-10-06T14:00:00";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", SEAL);
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult initiateRegulated(String actor, String evidenceKey, String requestId,
                                        String toCustodian, List<String> deadlines) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("toCustodian", toCustodian);
        body.put("deadlines", deadlines);
        return mockMvc.perform(post("/api/evidence/{key}/regulated-transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult signin(String actor, String evidenceKey, String requestId,
                             int seq, String sealNo, String arrivedAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("checkpointSeq", seq);
        body.put("sealNo", sealNo);
        body.put("arrivedAt", arrivedAt);
        return mockMvc.perform(post("/api/evidence/{key}/checkpoint-signins", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decide(String actor, String evidenceKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/{action}", evidenceKey, action)
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 准备一件 alice 保管的证物并发起 alice -> bob 的两点受监管交接。 */
    private String pendingRegulated() throws Exception {
        String evidenceKey = uniqueKey("EV");
        assertThat(intake("alice", evidenceKey).getResponse().getStatus()).isEqualTo(201);
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(200);
        return evidenceKey;
    }

    @Test
    void fullRegulatedFlowCompletesRouteThenAcceptSwitchesCustodian() throws Exception {
        String evidenceKey = pendingRegulated();

        MvcResult s1 = signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00");
        assertThat(s1.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(s1).get("checkpointSeq").asInt()).isEqualTo(1);
        MvcResult s2 = signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, "2026-10-06T11:00:00");
        assertThat(s2.getResponse().getStatus()).isEqualTo(200);

        MvcResult accepted = decide("bob", evidenceKey, "accept");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(accepted).get("custodianId").asText()).isEqualTo("bob");
        assertThat(body(accepted).get("status").asText()).isEqualTo("SEALED");

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("checkpoints")).hasSize(2);
        assertThat(chain.get("checkpoints").get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(chain.get("checkpoints").get(0).get("deadlineUtc").asText()).startsWith(D1);
        assertThat(chain.get("signins")).hasSize(2);
        assertThat(chain.get("anomalies")).isEmpty();
        assertThat(chain.get("transfers").get(0).get("transferType").asText()).isEqualTo("REGULATED");
        assertThat(chain.get("transfers").get(0).get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void initiateDeclaresCheckpointsAndReturnsRegulatedPendingTransfer() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        MvcResult result = initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob",
                List.of(D1, D2, D3));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode transfer = body(result);
        assertThat(transfer.get("transferType").asText()).isEqualTo("REGULATED");
        assertThat(transfer.get("status").asText()).isEqualTo("PENDING");
        assertThat(transfer.get("frozen").asBoolean()).isFalse();
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("checkpoints")).hasSize(3);
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("TRANSFER_PENDING");
    }

    @Test
    void initiateWithInvalidCheckpointCountOrOrderReturns400() throws Exception {
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        // 少于 2 个检查点
        assertThat(initiateRegulated("alice", ev1, uniqueKey("REQ"), "bob", List.of(D1))
                .getResponse().getStatus()).isEqualTo(400);
        // 多于 8 个检查点
        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add("2026-10-06T1" + i + ":00:00");
        }
        assertThat(initiateRegulated("alice", ev1, uniqueKey("REQ"), "bob", nine)
                .getResponse().getStatus()).isEqualTo(400);
        // 截止时刻未递增（倒退与相等均非法）
        assertThat(initiateRegulated("alice", ev1, uniqueKey("REQ"), "bob", List.of(D2, D1))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(initiateRegulated("alice", ev1, uniqueKey("REQ"), "bob", List.of(D1, D1))
                .getResponse().getStatus()).isEqualTo(400);
        // 全部失败后证物仍可正常发起
        assertThat(initiateRegulated("alice", ev1, uniqueKey("REQ"), "bob", List.of(D1, D2))
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void initiateByNonCustodianReturns403BeforeReplay() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String requestId = uniqueKey("REQ");
        MvcResult first = initiateRegulated("alice", evidenceKey, requestId, "bob", List.of(D1, D2));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 越权先于重放：非保管人使用同一 requestId 同参数，返回 403 而非重放 200
        assertThat(initiateRegulated("mallory", evidenceKey, requestId, "bob", List.of(D1, D2))
                .getResponse().getStatus()).isEqualTo(403);
        // 保管人同键同参重放返回首次完整响应
        MvcResult replay = initiateRegulated("alice", evidenceKey, requestId, "bob", List.of(D1, D2));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键异参返回 409
        assertThat(initiateRegulated("alice", evidenceKey, requestId, "carol", List.of(D1, D2))
                .getResponse().getStatus()).isEqualTo(409);
    }
    @Test
    void signinSkipsCheckpointFailsAndRecordsNothing() throws Exception {
        // 合成 P1、P2 两点，直接签到 P2 应失败且无签到
        String evidenceKey = pendingRegulated();
        MvcResult skipped = signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, "2026-10-06T09:00:00");
        assertThat(skipped.getResponse().getStatus()).isEqualTo(409);
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("signins")).isEmpty();
        assertThat(chain.get("anomalies")).isEmpty();
    }

    @Test
    void signinByNonTransporterReturns403() throws Exception {
        String evidenceKey = pendingRegulated();
        assertThat(signin("alice", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(signin("carol", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(chain(evidenceKey).get("signins")).isEmpty();
    }

    @Test
    void arrivalEqualToDeadlineIsLegalAndBackwardsArrivalRejected() throws Exception {
        String evidenceKey = pendingRegulated();
        // 到达时刻等于截止仍合法
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, D1)
                .getResponse().getStatus()).isEqualTo(200);
        // 到达时刻倒退（早于上一签到点）拒绝
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, "2026-10-06T08:00:00")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("signins")).hasSize(1);
        // 与上一签到点同时刻合法
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, D1)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void lateArrivalRecordsAnomalyFreezesAndBlocksAccept() throws Exception {
        String evidenceKey = pendingRegulated();
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(200);

        // P2 晚于截止：记录异常并冻结，返回 200 异常视图
        MvcResult anomaly = signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, "2026-10-06T13:00:00");
        assertThat(anomaly.getResponse().getStatus()).isEqualTo(200);
        JsonNode anomalyBody = body(anomaly);
        assertThat(anomalyBody.get("reason").asText()).isEqualTo("LATE");
        assertThat(anomalyBody.get("checkpointSeq").asInt()).isEqualTo(2);

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("anomalies")).hasSize(1);
        assertThat(chain.get("signins")).hasSize(1);
        assertThat(chain.get("transfers").get(0).get("frozen").asBoolean()).isTrue();

        // 冻结后：普通新签到不能继续，收件人不能接收
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, "2026-10-06T11:00:00")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("bob", evidenceKey, "accept").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void sealMismatchRecordsAnomalyAndReceiverCannotAccept() throws Exception {
        // P1 成功后 P2 封条错误形成异常，接收必须被拒绝
        String evidenceKey = pendingRegulated();
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(200);

        MvcResult anomaly = signin("bob", evidenceKey, uniqueKey("REQ"), 2, "SEAL-WRONG",
                "2026-10-06T11:00:00");
        assertThat(anomaly.getResponse().getStatus()).isEqualTo(200);
        JsonNode anomalyBody = body(anomaly);
        assertThat(anomalyBody.get("reason").asText()).isEqualTo("SEAL_MISMATCH");
        assertThat(anomalyBody.get("expectedSealNo").asText()).isEqualTo(SEAL);
        assertThat(anomalyBody.get("submittedSealNo").asText()).isEqualTo("SEAL-WRONG");

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("signins")).hasSize(1);
        assertThat(chain.get("anomalies")).hasSize(1);
        assertThat(chain.get("transfers").get(0).get("frozen").asBoolean()).isTrue();
        assertThat(decide("bob", evidenceKey, "accept").getResponse().getStatus()).isEqualTo(422);
        assertThat(chain(evidenceKey).get("evidence").get("custodianId").asText()).isEqualTo("alice");
    }

    @Test
    void acceptWithMissingSigninsReturns422() throws Exception {
        String evidenceKey = pendingRegulated();
        // 未签到任何检查点
        assertThat(decide("bob", evidenceKey, "accept").getResponse().getStatus()).isEqualTo(422);
        // 只签 P1，路由不完整
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("bob", evidenceKey, "accept").getResponse().getStatus()).isEqualTo(422);
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chain.get("transfers").get(0).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void custodianCancelsFrozenTransferAndReinitiatesWithOldChainPreserved() throws Exception {
        String evidenceKey = pendingRegulated();
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 2, "SEAL-WRONG", "2026-10-06T11:00:00")
                .getResponse().getStatus()).isEqualTo(200);

        // 接收人不能取消，原保管人可取消冻结交接
        assertThat(decide("bob", evidenceKey, "cancel").getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("alice", evidenceKey, "cancel").getResponse().getStatus()).isEqualTo(200);

        // 重新发起新交接（新截止名单），旧异常与签到保留
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "bob",
                List.of(D2, D3)).getResponse().getStatus()).isEqualTo(200);
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T11:30:00")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 2, SEAL, "2026-10-06T13:00:00")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(decide("bob", evidenceKey, "accept").getResponse().getStatus()).isEqualTo(200);

        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(chain.get("transfers")).hasSize(2);
        assertThat(chain.get("transfers").get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(chain.get("transfers").get(0).get("frozen").asBoolean()).isTrue();
        assertThat(chain.get("transfers").get(1).get("status").asText()).isEqualTo("ACCEPTED");
        // 旧链：2 点名单 + 1 签到 + 1 异常原样保留；新链再追加 2 点 + 2 签到
        assertThat(chain.get("checkpoints")).hasSize(4);
        assertThat(chain.get("signins")).hasSize(3);
        assertThat(chain.get("anomalies")).hasSize(1);
        assertThat(chain.get("anomalies").get(0).get("reason").asText()).isEqualTo("SEAL_MISMATCH");
    }
    @Test
    void signinIdempotencyReplaySameKeyAndFailureDoesNotOccupyKey() throws Exception {
        String evidenceKey = pendingRegulated();
        String requestId = uniqueKey("REQ");
        MvcResult first = signin("bob", evidenceKey, requestId, 1, SEAL, "2026-10-06T09:00:00");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 同键同参重放首次完整响应
        MvcResult replay = signin("bob", evidenceKey, requestId, 1, SEAL, "2026-10-06T09:00:00");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键异参 409
        assertThat(signin("bob", evidenceKey, requestId, 1, SEAL, "2026-10-06T09:30:00")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(evidenceKey).get("signins")).hasSize(1);

        // 失败不占键：跳点（应为 P2 却提交 P3）失败后同一 requestId 修正参数可成功
        String failedKey = uniqueKey("REQ");
        assertThat(signin("bob", evidenceKey, failedKey, 3, SEAL, "2026-10-06T11:00:00")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(signin("bob", evidenceKey, failedKey, 2, SEAL, "2026-10-06T11:00:00")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anomalyResponseIsStoredAndReplayable() throws Exception {
        String evidenceKey = pendingRegulated();
        assertThat(signin("bob", evidenceKey, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(200);
        String requestId = uniqueKey("REQ");
        MvcResult first = signin("bob", evidenceKey, requestId, 2, "SEAL-WRONG", "2026-10-06T11:00:00");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        MvcResult replay = signin("bob", evidenceKey, requestId, 2, "SEAL-WRONG", "2026-10-06T11:00:00");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        JsonNode chain = chain(evidenceKey);
        assertThat(chain.get("anomalies")).hasSize(1);
    }

    @Test
    void regulatedPendingBlocksBorrowAndOtherTransfers() throws Exception {
        String evidenceKey = pendingRegulated();
        // 借出仍被阻断
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("commandKey", uniqueKey("CMD"));
        loan.put("loanKey", uniqueKey("LOAN"));
        loan.put("borrowerId", "carol");
        loan.put("purpose", "review");
        loan.put("dueAt", LocalDateTime.now().plusHours(1).toString());
        MvcResult borrow = mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loan)))
                .andReturn();
        assertThat(borrow.getResponse().getStatus()).isEqualTo(409);
        // 普通交接与第二条受监管交接均被阻断（同一证物仅一条在途交接）
        Map<String, Object> regular = new LinkedHashMap<>();
        regular.put("commandKey", uniqueKey("CMD"));
        regular.put("toCustodian", "carol");
        MvcResult regularTransfer = mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regular)))
                .andReturn();
        assertThat(regularTransfer.getResponse().getStatus()).isEqualTo(409);
        assertThat(initiateRegulated("alice", evidenceKey, uniqueKey("REQ"), "carol", List.of(D1, D2))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void signinRejectedOnRegularTransferAndAfterRouteComplete() throws Exception {
        // 普通交接不支持检查点签到
        String ev1 = uniqueKey("EV");
        intake("alice", ev1);
        Map<String, Object> regular = new LinkedHashMap<>();
        regular.put("commandKey", uniqueKey("CMD"));
        regular.put("toCustodian", "bob");
        mockMvc.perform(post("/api/evidence/{key}/transfers", ev1)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(regular)))
                .andReturn();
        assertThat(signin("bob", ev1, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(409);

        // 路由完成后继续签到拒绝
        String ev2 = pendingRegulated();
        assertThat(signin("bob", ev2, uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(signin("bob", ev2, uniqueKey("REQ"), 2, SEAL, "2026-10-06T11:00:00")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(signin("bob", ev2, uniqueKey("REQ"), 3, SEAL, "2026-10-06T11:30:00")
                .getResponse().getStatus()).isEqualTo(409);
        // 取消后签到拒绝（不存在取消后的新签到）
        assertThat(decide("alice", ev2, "cancel").getResponse().getStatus()).isEqualTo(200);
        assertThat(signin("bob", ev2, uniqueKey("REQ"), 1, SEAL, "2026-10-06T11:40:00")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(chain(ev2).get("signins")).hasSize(2);
    }

    @Test
    void signinOnMissingEvidenceReturns404() throws Exception {
        assertThat(signin("bob", uniqueKey("EV"), uniqueKey("REQ"), 1, SEAL, "2026-10-06T09:00:00")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(initiateRegulated("alice", uniqueKey("EV"), uniqueKey("REQ"), "bob",
                List.of(D1, D2)).getResponse().getStatus()).isEqualTo(404);
    }
}