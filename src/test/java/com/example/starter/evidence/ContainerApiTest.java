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
 * 封存箱 API 测试：建箱、装箱规则（版本、深度、环、重复成员、保管人、件数上限）、
 * 拆箱、待交接冻结与箱内证物单件操作限制。
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

    private MvcResult postJson(String url, String actor, Map<String, Object> body) throws Exception {
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
        return postJson("/api/evidence", actor, body);
    }

    private MvcResult createBox(String actor, String requestId, String containerKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("containerKey", containerKey);
        body.put("sealNo", "BOXSEAL-1");
        return postJson("/api/containers", actor, body);
    }

    private String createBox(String actor, String containerKey) throws Exception {
        MvcResult result = createBox(actor, uniqueKey("REQ"), containerKey);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return containerKey;
    }

    private MvcResult pack(String actor, String containerKey, int expectedVersion,
                           List<String> evidenceKeys, List<String> containerKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("expectedVersion", expectedVersion);
        body.put("evidenceKeys", evidenceKeys);
        body.put("containerKeys", containerKeys);
        return postJson("/api/containers/" + containerKey + "/members/add", actor, body);
    }

    private MvcResult unpack(String actor, String containerKey, int expectedVersion,
                             List<String> evidenceKeys, List<String> containerKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("expectedVersion", expectedVersion);
        body.put("evidenceKeys", evidenceKeys);
        body.put("containerKeys", containerKeys);
        return postJson("/api/containers/" + containerKey + "/members/remove", actor, body);
    }

    private MvcResult initiateBox(String actor, String containerKey, int expectedVersion,
                                  String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("toCustodian", toCustodian);
        body.put("expectedVersion", expectedVersion);
        return postJson("/api/containers/" + containerKey + "/transfers", actor, body);
    }

    private MvcResult decideBox(String actor, String containerKey, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        return postJson("/api/containers/" + containerKey + "/transfers/" + action, actor, body);
    }

    private MvcResult inspectEvidence(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson("/api/evidence/" + evidenceKey + "/seal-inspections", actor, body);
    }

    private JsonNode tree(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/{key}/tree", containerKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void createBoxStartsSealedAtVersionOne() throws Exception {
        String box = uniqueKey("BOX");
        MvcResult result = createBox("alice", uniqueKey("REQ"), box);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("containerKey").asText()).isEqualTo(box);
        assertThat(body.get("custodianId").asText()).isEqualTo("alice");
        assertThat(body.get("status").asText()).isEqualTo("SEALED");
        assertThat(body.get("sealStatus").asText()).isEqualTo("INTACT");
        assertThat(body.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void createBoxDuplicateKeyReturns409() throws Exception {
        String box = uniqueKey("BOX");
        assertThat(createBox("alice", uniqueKey("REQ"), box).getResponse().getStatus()).isEqualTo(201);
        assertThat(createBox("alice", uniqueKey("REQ"), box).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createBoxIdempotentReplayAndParamConflict() throws Exception {
        String box = uniqueKey("BOX");
        String requestId = uniqueKey("REQ");
        MvcResult first = createBox("alice", requestId, box);
        MvcResult replay = createBox("alice", requestId, box);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同 requestId 改参 → 409
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("requestId", requestId);
        other.put("containerKey", uniqueKey("BOX"));
        other.put("sealNo", "BOXSEAL-2");
        assertThat(postJson("/api/containers", "alice", other).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void packEvidenceIncrementsRootVersionAndListsTree() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);

        MvcResult packed = pack("alice", box, 1, List.of(evidence), List.of());
        assertThat(packed.getResponse().getStatus()).isEqualTo(200);
        JsonNode tree = objectMapper.readTree(packed.getResponse().getContentAsString());
        assertThat(tree.get("container").get("version").asInt()).isEqualTo(2);
        assertThat(tree.get("evidenceItems")).hasSize(1);
        assertThat(tree.get("evidenceItems").get(0).get("evidenceKey").asText()).isEqualTo(evidence);

        // 旧版本号再次装箱 → 409 版本冲突
        assertThat(pack("alice", box, 1, List.of(uniqueKey("EV2")), List.of())
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void packNestingLimitedToThreeLevels() throws Exception {
        String boxA = uniqueKey("BOX");
        String boxB = uniqueKey("BOX");
        String boxC = uniqueKey("BOX");
        String boxD = uniqueKey("BOX");
        createBox("alice", boxA);
        createBox("alice", boxB);
        createBox("alice", boxC);
        createBox("alice", boxD);

        assertThat(pack("alice", boxA, 1, List.of(), List.of(boxB)).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(pack("alice", boxB, 2, List.of(), List.of(boxC)).getResponse().getStatus())
                .isEqualTo(200);
        // 第 4 层 → 422，且整次不改变归属
        assertThat(pack("alice", boxC, 3, List.of(), List.of(boxD)).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(tree(boxA).get("container").get("version").asInt()).isEqualTo(3);
        assertThat(tree(boxD).get("container").get("custodianId").asText()).isEqualTo("alice");

        // 第 3 层仍可装证物
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        assertThat(pack("alice", boxC, 3, List.of(evidence), List.of()).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void packCycleReturns422AndTreeUnchanged() throws Exception {
        String boxA = uniqueKey("BOX");
        String boxB = uniqueKey("BOX");
        createBox("alice", boxA);
        createBox("alice", boxB);
        assertThat(pack("alice", boxA, 1, List.of(), List.of(boxB)).getResponse().getStatus())
                .isEqualTo(200);

        // 把祖先箱 A 装进后代箱 B → 环，422
        assertThat(pack("alice", boxB, 2, List.of(), List.of(boxA)).getResponse().getStatus())
                .isEqualTo(422);
        JsonNode tree = tree(boxA);
        assertThat(tree.get("container").get("version").asInt()).isEqualTo(2);
        assertThat(tree.get("childContainers")).hasSize(1);
        assertThat(tree.get("childContainers").get(0).get("container").get("containerKey").asText())
                .isEqualTo(boxB);
    }

    @Test
    void packDuplicateMemberReturns422() throws Exception {
        String box = uniqueKey("BOX");
        String other = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        createBox("alice", other);
        intake("alice", evidence);

        // 同一请求内重复成员 → 422
        assertThat(pack("alice", box, 1, List.of(evidence, evidence), List.of())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(pack("alice", box, 1, List.of(evidence), List.of()).getResponse().getStatus())
                .isEqualTo(200);
        // 已在箱内的证物再次装箱（同箱或他箱）→ 422
        assertThat(pack("alice", box, 2, List.of(evidence), List.of())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(pack("alice", other, 1, List.of(evidence), List.of())
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void packMixedCustodianReturns422AndOwnershipUnchanged() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("bob", evidence);

        assertThat(pack("alice", box, 1, List.of(evidence), List.of())
                .getResponse().getStatus()).isEqualTo(422);
        JsonNode tree = tree(box);
        assertThat(tree.get("container").get("version").asInt()).isEqualTo(1);
        assertThat(tree.get("evidenceItems")).isEmpty();
    }

    @Test
    void packBeyondTenEvidencePerRootReturns422() throws Exception {
        String box = uniqueKey("BOX");
        createBox("alice", box);
        List<String> ten = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String evidence = uniqueKey("EV");
            intake("alice", evidence);
            ten.add(evidence);
        }
        assertThat(pack("alice", box, 1, ten, List.of()).getResponse().getStatus()).isEqualTo(200);

        String eleventh = uniqueKey("EV");
        intake("alice", eleventh);
        assertThat(pack("alice", box, 2, List.of(eleventh), List.of())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(tree(box).get("evidenceItems")).hasSize(10);
    }

    @Test
    void unpackRemovesMemberAndRestoresSingleOperations() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);
        assertThat(pack("alice", box, 1, List.of(evidence), List.of()).getResponse().getStatus())
                .isEqualTo(200);

        MvcResult unpacked = unpack("alice", box, 2, List.of(evidence), List.of());
        assertThat(unpacked.getResponse().getStatus()).isEqualTo(200);
        JsonNode tree = objectMapper.readTree(unpacked.getResponse().getContentAsString());
        assertThat(tree.get("container").get("version").asInt()).isEqualTo(3);
        assertThat(tree.get("evidenceItems")).isEmpty();

        // 拆出后单件交接恢复可用
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", "bob");
        assertThat(postJson("/api/evidence/" + evidence + "/transfers", "alice", body)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void unpackNonMemberReturns422() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);

        assertThat(unpack("alice", box, 1, List.of(evidence), List.of())
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(tree(box).get("container").get("version").asInt()).isEqualTo(1);
    }

    @Test
    void boxedEvidenceBlocksLoanAndSingleTransferButAllowsInspection() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);
        pack("alice", box, 1, List.of(evidence), List.of());

        // 箱内证物单件交接 → 409
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("commandKey", uniqueKey("CMD"));
        transfer.put("toCustodian", "bob");
        assertThat(postJson("/api/evidence/" + evidence + "/transfers", "alice", transfer)
                .getResponse().getStatus()).isEqualTo(409);

        // 箱内证物借出 → 409
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("commandKey", uniqueKey("CMD"));
        loan.put("loanKey", uniqueKey("LOAN"));
        loan.put("borrowerId", "bob");
        loan.put("purpose", "exam");
        loan.put("dueAt", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .plusHours(1).toLocalDateTime().toString());
        assertThat(postJson("/api/evidence/" + evidence + "/loans", "alice", loan)
                .getResponse().getStatus()).isEqualTo(409);

        // 原封条异常检查仍允许
        assertThat(inspectEvidence("alice", evidence, true).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void memberChangesFrozenDuringPendingTransfer() throws Exception {
        String box = uniqueKey("BOX");
        String e1 = uniqueKey("EV");
        String e2 = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", e1);
        intake("alice", e2);
        pack("alice", box, 1, List.of(e1), List.of());
        assertThat(initiateBox("alice", box, 2, "bob").getResponse().getStatus()).isEqualTo(200);

        // 待交接期间禁改成员
        assertThat(pack("alice", box, 2, List.of(e2), List.of()).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(unpack("alice", box, 2, List.of(e1), List.of()).getResponse().getStatus())
                .isEqualTo(409);
        // 但允许原保管人检查成员封条
        assertThat(inspectEvidence("alice", e1, true).getResponse().getStatus()).isEqualTo(200);

        // 取消后解冻，成员变更恢复
        assertThat(decideBox("alice", box, "cancel").getResponse().getStatus()).isEqualTo(200);
        assertThat(pack("alice", box, 2, List.of(e2), List.of()).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(tree(box).get("evidenceItems")).hasSize(2);
    }

    @Test
    void operateOnMissingContainerReturns404() throws Exception {
        String missing = uniqueKey("BOX");
        assertThat(pack("alice", missing, 1, List.of(uniqueKey("EV")), List.of())
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(initiateBox("alice", missing, 1, "bob").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(decideBox("alice", missing, "accept").getResponse().getStatus())
                .isEqualTo(404);
        MvcResult treeResult = mockMvc.perform(get("/api/containers/{key}/tree", missing))
                .andReturn();
        assertThat(treeResult.getResponse().getStatus()).isEqualTo(404);
    }
}
