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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 封存箱 API 主流程与失败分支测试：嵌套层级、成员约束、整树交接、
 * 封条异常前置条件与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult postJson(String url, String actor, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        MvcResult result = postJson("/api/evidence", actor, body);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return result;
    }

    private MvcResult createBox(String actor, String containerKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("containerKey", containerKey);
        body.put("sealNo", "BOX-SEAL-1");
        return postJson("/api/containers", actor, body);
    }

    private String createBoxOk(String actor, String containerKey) throws Exception {
        MvcResult result = createBox(actor, containerKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return containerKey;
    }

    private MvcResult pack(String actor, String containerKey, String memberType,
                           String memberKey, int expectedVersion) throws Exception {
        return pack(actor, containerKey, uniqueKey("REQ"), memberType, memberKey, expectedVersion);
    }

    private MvcResult pack(String actor, String containerKey, String requestId, String memberType,
                           String memberKey, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("memberType", memberType);
        body.put("memberKey", memberKey);
        body.put("expectedVersion", expectedVersion);
        return postJson("/api/containers/" + containerKey + "/members", actor, body);
    }

    private MvcResult unpack(String actor, String containerKey, String memberType,
                             String memberKey, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("memberType", memberType);
        body.put("memberKey", memberKey);
        body.put("expectedVersion", expectedVersion);
        return postJson("/api/containers/" + containerKey + "/members/remove", actor, body);
    }

    private MvcResult initiate(String actor, String containerKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("toCustodian", toCustodian);
        return postJson("/api/containers/" + containerKey + "/transfers", actor, body);
    }

    private MvcResult decide(String actor, String containerKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        return postJson("/api/containers/" + containerKey + "/transfers/" + action, actor, body);
    }

    private MvcResult inspectBox(String actor, String containerKey, boolean passed)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson("/api/containers/" + containerKey + "/seal-inspections", actor, body);
    }

    private MvcResult inspectEvidence(String actor, String evidenceKey, boolean passed)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson("/api/evidence/" + evidenceKey + "/seal-inspections", actor, body);
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-1");
        body.put("purpose", "examination");
        body.put("dueAt", LocalDateTime.now().plusHours(1).toString());
        return postJson("/api/evidence/" + evidenceKey + "/loans", actor, body);
    }

    private MvcResult transferEvidence(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return postJson("/api/evidence/" + evidenceKey + "/transfers", actor, body);
    }

    private JsonNode tree(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/{key}/tree", containerKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode evidenceChain(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createContainerReturns201WithVersionOne() throws Exception {
        String box = uniqueKey("BOX");
        MvcResult result = createBox("alice", box);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("containerKey").asText()).isEqualTo(box);
        assertThat(body.get("custodianId").asText()).isEqualTo("alice");
        assertThat(body.get("status").asText()).isEqualTo("SEALED");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("parentKey").isNull()).isTrue();
        assertThat(body.get("rootKey").asText()).isEqualTo(box);
    }

    @Test
    void createDuplicateKeyReturns409AndIdempotentReplayReturnsFirstResult() throws Exception {
        String box = uniqueKey("BOX");
        assertThat(createBox("alice", box).getResponse().getStatus()).isEqualTo(201);
        assertThat(createBox("alice", box).getResponse().getStatus()).isEqualTo(409);

        String requestId = uniqueKey("REQ");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("containerKey", uniqueKey("BOX"));
        body.put("sealNo", "BOX-SEAL-1");
        MvcResult first = postJson("/api/containers", "alice", body);
        MvcResult replay = postJson("/api/containers", "alice", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        body.put("sealNo", "BOX-SEAL-2");
        assertThat(postJson("/api/containers", "alice", body).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void packEvidenceIncrementsRootVersionAndUnpackRestores() throws Exception {
        String box = createBoxOk("alice", uniqueKey("BOX"));
        String evidence = uniqueKey("EV");
        intake("alice", evidence);

        MvcResult packed = pack("alice", box, "EVIDENCE", evidence, 1);
        assertThat(packed.getResponse().getStatus()).isEqualTo(200);
        JsonNode root = objectMapper.readTree(packed.getResponse().getContentAsString());
        assertThat(root.get("version").asInt()).isEqualTo(2);

        JsonNode tree = tree(box);
        assertThat(tree.get("members")).hasSize(1);
        assertThat(tree.get("members").get(0).get("memberKey").asText()).isEqualTo(evidence);
        assertThat(tree.get("members").get(0).get("memberType").asText()).isEqualTo("EVIDENCE");

        // 版本不匹配 409
        assertThat(unpack("alice", box, "EVIDENCE", evidence, 1).getResponse().getStatus())
                .isEqualTo(409);
        // 拆箱成功，版本再加一
        MvcResult unpacked = unpack("alice", box, "EVIDENCE", evidence, 2);
        assertThat(unpacked.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(unpacked.getResponse().getContentAsString())
                .get("version").asInt()).isEqualTo(3);
        assertThat(tree(box).get("members")).isEmpty();
        // 拆出后证物恢复单件交接能力
        assertThat(transferEvidence("alice", evidence, "bob").getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void packValidationFailuresReturn422Or409WithoutChangingOwnership() throws Exception {
        String box = createBoxOk("alice", uniqueKey("BOX"));
        String otherBox = createBoxOk("bob", uniqueKey("BOX"));
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        String bobEvidence = uniqueKey("EV");
        intake("bob", bobEvidence);

        // 混入不同保管人 422
        assertThat(pack("alice", box, "EVIDENCE", bobEvidence, 1).getResponse().getStatus())
                .isEqualTo(422);
        // 非保管人操作 409
        assertThat(pack("mallory", box, "EVIDENCE", evidence, 1).getResponse().getStatus())
                .isEqualTo(409);
        // 子箱保管人不同 422
        assertThat(pack("alice", box, "BOX", otherBox, 1).getResponse().getStatus())
                .isEqualTo(422);

        assertThat(pack("alice", box, "EVIDENCE", evidence, 1).getResponse().getStatus())
                .isEqualTo(200);
        // 重复成员 422
        assertThat(pack("alice", box, "EVIDENCE", evidence, 2).getResponse().getStatus())
                .isEqualTo(422);
        // 归属未改变
        assertThat(evidenceChain(evidence).get("evidence").get("custodianId").asText())
                .isEqualTo("alice");
        assertThat(evidenceChain(bobEvidence).get("evidence").get("custodianId").asText())
                .isEqualTo("bob");
    }

    @Test
    void packBorrowedOrPendingEvidenceReturns409() throws Exception {
        String box = createBoxOk("alice", uniqueKey("BOX"));
        String borrowed = uniqueKey("EV");
        intake("alice", borrowed);
        assertThat(borrow("alice", borrowed).getResponse().getStatus()).isEqualTo(200);
        assertThat(pack("alice", box, "EVIDENCE", borrowed, 1).getResponse().getStatus())
                .isEqualTo(409);

        String pending = uniqueKey("EV");
        intake("alice", pending);
        assertThat(transferEvidence("alice", pending, "bob").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(pack("alice", box, "EVIDENCE", pending, 1).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void packDepthLimitAndCycleReturn422() throws Exception {
        String root = createBoxOk("alice", uniqueKey("BOX"));
        String child = createBoxOk("alice", uniqueKey("BOX"));
        String grandchild = createBoxOk("alice", uniqueKey("BOX"));
        String extra = createBoxOk("alice", uniqueKey("BOX"));

        assertThat(pack("alice", root, "BOX", child, 1).getResponse().getStatus()).isEqualTo(200);
        assertThat(pack("alice", child, "BOX", grandchild, 2).getResponse().getStatus())
                .isEqualTo(200);
        // 第 4 层超限 422
        assertThat(pack("alice", grandchild, "BOX", extra, 3).getResponse().getStatus())
                .isEqualTo(422);
        // 环：把根箱装入自己的子箱 422
        assertThat(pack("alice", grandchild, "BOX", root, 3).getResponse().getStatus())
                .isEqualTo(422);
        // 第 3 层箱内仍可装证物（证物不计层）
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        assertThat(pack("alice", grandchild, "EVIDENCE", evidence, 3).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode tree = tree(root);
        assertThat(tree.get("depth").asInt()).isEqualTo(1);
        JsonNode childNode = tree.get("members").get(0).get("child");
        assertThat(childNode.get("depth").asInt()).isEqualTo(2);
        assertThat(childNode.get("members").get(0).get("child").get("depth").asInt()).isEqualTo(3);
    }

    @Test
    void packEleventhEvidenceIntoRootTreeReturns422() throws Exception {
        String root = createBoxOk("alice", uniqueKey("BOX"));
        String child = createBoxOk("alice", uniqueKey("BOX"));
        assertThat(pack("alice", root, "BOX", child, 1).getResponse().getStatus()).isEqualTo(200);
        for (int i = 0; i < 10; i++) {
            String evidence = uniqueKey("EV");
            intake("alice", evidence);
            String target = i < 5 ? root : child;
            assertThat(pack("alice", target, "EVIDENCE", evidence, 2 + i)
                    .getResponse().getStatus()).isEqualTo(200);
        }
        String eleventh = uniqueKey("EV");
        intake("alice", eleventh);
        assertThat(pack("alice", root, "EVIDENCE", eleventh, 12).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void boxedEvidenceBlocksLoanAndSingleTransferButAllowsInspection() throws Exception {
        String box = createBoxOk("alice", uniqueKey("BOX"));
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        assertThat(pack("alice", box, "EVIDENCE", evidence, 1).getResponse().getStatus())
                .isEqualTo(200);

        // 箱内证物：借出与单件交接 409
        assertThat(borrow("alice", evidence).getResponse().getStatus()).isEqualTo(409);
        assertThat(transferEvidence("alice", evidence, "bob").getResponse().getStatus())
                .isEqualTo(409);
        // 原封条异常检查仍允许
        assertThat(inspectEvidence("alice", evidence, true).getResponse().getStatus())
                .isEqualTo(200);
        // 核验失败使证物进入 SEAL_BROKEN，进而使根箱不能交接（422）
        assertThat(inspectEvidence("alice", evidence, false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(evidenceChain(evidence).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        assertThat(initiate("alice", box, "bob").getResponse().getStatus()).isEqualTo(422);
        // 箱封条正常不代表成员全正常：箱自身核验通过，根箱仍不能交接
        assertThat(inspectBox("alice", box, true).getResponse().getStatus()).isEqualTo(200);
        assertThat(initiate("alice", box, "bob").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void containerSealInspectionFailureBlocksRootTransfer() throws Exception {
        String box = createBoxOk("alice", uniqueKey("BOX"));
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        assertThat(pack("alice", box, "EVIDENCE", evidence, 1).getResponse().getStatus())
                .isEqualTo(200);

        assertThat(inspectBox("alice", box, false).getResponse().getStatus()).isEqualTo(200);
        JsonNode tree = tree(box);
        assertThat(tree.get("status").asText()).isEqualTo("SEAL_BROKEN");
        assertThat(initiate("alice", box, "bob").getResponse().getStatus()).isEqualTo(422);
        // 非保管人核验 409
        assertThat(inspectBox("mallory", box, true).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void rootTransferFreezesTreeAndAcceptSwitchesWholeSubtree() throws Exception {
        String root = createBoxOk("alice", uniqueKey("BOX"));
        String child = createBoxOk("alice", uniqueKey("BOX"));
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        assertThat(pack("alice", root, "EVIDENCE", e1, 1).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(pack("alice", root, "BOX", child, 2).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(pack("alice", child, "EVIDENCE", e2, 3).getResponse().getStatus())
                .isEqualTo(200);

        MvcResult initiated = initiate("alice", root, "bob");
        assertThat(initiated.getResponse().getStatus()).isEqualTo(200);
        JsonNode transfer = objectMapper.readTree(initiated.getResponse().getContentAsString());
        assertThat(transfer.get("status").asText()).isEqualTo("PENDING");

        // 冻结：整树箱 TRANSFER_PENDING，版本不变
        JsonNode frozen = tree(root);
        assertThat(frozen.get("status").asText()).isEqualTo("TRANSFER_PENDING");
        assertThat(frozen.get("version").asInt()).isEqualTo(4);
        assertThat(frozen.get("members").get(1).get("child").get("status").asText())
                .isEqualTo("TRANSFER_PENDING");
        // 待交接期间禁改成员
        String e3 = uniqueKey("EV");
        intake("alice", e3);
        assertThat(pack("alice", root, "EVIDENCE", e3, 4).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(unpack("alice", root, "EVIDENCE", e1, 4).getResponse().getStatus())
                .isEqualTo(409);
        // 允许原保管人检查成员封条
        assertThat(inspectEvidence("alice", e1, true).getResponse().getStatus())
                .isEqualTo(200);
        // 非根箱不能发起交接
        assertThat(initiate("alice", child, "bob").getResponse().getStatus()).isEqualTo(409);

        MvcResult accepted = decide("bob", root, "accept");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode acceptedRoot = objectMapper.readTree(accepted.getResponse().getContentAsString());
        assertThat(acceptedRoot.get("custodianId").asText()).isEqualTo("bob");
        assertThat(acceptedRoot.get("status").asText()).isEqualTo("SEALED");
        assertThat(acceptedRoot.get("version").asInt()).isEqualTo(4);

        JsonNode tree = tree(root);
        assertThat(tree.get("custodianId").asText()).isEqualTo("bob");
        assertThat(tree.get("members").get(1).get("child").get("custodianId").asText())
                .isEqualTo("bob");
        assertThat(evidenceChain(e1).get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(evidenceChain(e2).get("evidence").get("custodianId").asText()).isEqualTo("bob");
        assertThat(evidenceChain(e1).get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void acceptWithBrokenMemberReturns422AndKeepsFrozenTreeIntact() throws Exception {
        // 合成场景：根箱含 E1 与子箱内 E2；待交接时 E2 封条异常
        String root = createBoxOk("alice", uniqueKey("BOX"));
        String child = createBoxOk("alice", uniqueKey("BOX"));
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        intake("alice", e1);
        intake("alice", e2);
        pack("alice", root, "EVIDENCE", e1, 1);
        pack("alice", root, "BOX", child, 2);
        pack("alice", child, "EVIDENCE", e2, 3);
        assertThat(initiate("alice", root, "bob").getResponse().getStatus()).isEqualTo(200);

        // 待交接期间 E2 封条核验失败
        assertThat(inspectEvidence("alice", e2, false).getResponse().getStatus()).isEqualTo(200);
        // 整箱接收 422
        assertThat(decide("bob", root, "accept").getResponse().getStatus()).isEqualTo(422);
        // E1 保管人不改变
        assertThat(evidenceChain(e1).get("evidence").get("custodianId").asText())
                .isEqualTo("alice");
        assertThat(evidenceChain(e2).get("evidence").get("custodianId").asText())
                .isEqualTo("alice");
        // 冻结树仍列完整 E1 和 E2
        JsonNode tree = tree(root);
        assertThat(tree.get("status").asText()).isEqualTo("TRANSFER_PENDING");
        assertThat(tree.get("members")).hasSize(2);
        assertThat(tree.get("members").get(0).get("memberKey").asText()).isEqualTo(e1);
        JsonNode childNode = tree.get("members").get(1).get("child");
        assertThat(childNode.get("members")).hasSize(1);
        assertThat(childNode.get("members").get(0).get("memberKey").asText()).isEqualTo(e2);
        assertThat(childNode.get("members").get(0).get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        // 取消后整树解冻，E2 保持 SEAL_BROKEN，保管人不变
        assertThat(decide("alice", root, "cancel").getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(root).get("status").asText()).isEqualTo("SEALED");
        assertThat(evidenceChain(e2).get("evidence").get("status").asText())
                .isEqualTo("SEAL_BROKEN");
        assertThat(evidenceChain(e1).get("evidence").get("custodianId").asText())
                .isEqualTo("alice");
    }

    @Test
    void transferPermissionsFollowOriginalRules() throws Exception {
        String root = createBoxOk("alice", uniqueKey("BOX"));
        // 非保管人发起 409
        assertThat(initiate("mallory", root, "bob").getResponse().getStatus()).isEqualTo(409);
        // 接收人不能是本人 400
        assertThat(initiate("alice", root, "alice").getResponse().getStatus()).isEqualTo(400);
        assertThat(initiate("alice", root, "bob").getResponse().getStatus()).isEqualTo(200);
        // 非指定接收人接受 409
        assertThat(decide("carol", root, "accept").getResponse().getStatus()).isEqualTo(409);
        // 接收人不能取消 409，原保管人可取消
        assertThat(decide("bob", root, "cancel").getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("alice", root, "cancel").getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(root).get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void packIdempotentReplayDoesNotIncrementVersionTwice() throws Exception {
        String box = createBoxOk("alice", uniqueKey("BOX"));
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        String requestId = uniqueKey("REQ");

        MvcResult first = pack("alice", box, requestId, "EVIDENCE", evidence, 1);
        MvcResult replay = pack("alice", box, requestId, "EVIDENCE", evidence, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(tree(box).get("version").asInt()).isEqualTo(2);
        assertThat(tree(box).get("members")).hasSize(1);

        // 同键改参（不同期望版本）409
        assertThat(pack("alice", box, requestId, "EVIDENCE", evidence, 2)
                .getResponse().getStatus()).isEqualTo(409);
        // 失败不占键：版本不匹配失败后，同键修正参数可重新执行
        String retryId = uniqueKey("REQ");
        String extra = uniqueKey("EV");
        intake("alice", extra);
        assertThat(pack("alice", box, retryId, "EVIDENCE", extra, 1).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(pack("alice", box, retryId, "EVIDENCE", extra, 2).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void acceptIdempotentReplayReturnsFirstResponse() throws Exception {
        String root = createBoxOk("alice", uniqueKey("BOX"));
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        pack("alice", root, "EVIDENCE", evidence, 1);
        initiate("alice", root, "bob");

        String requestId = uniqueKey("REQ");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        MvcResult first = postJson("/api/containers/" + root + "/transfers/accept", "bob", body);
        MvcResult replay = postJson("/api/containers/" + root + "/transfers/accept", "bob", body);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(evidenceChain(evidence).get("evidence").get("custodianId").asText())
                .isEqualTo("bob");
    }
}
