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
 * 整箱交接 API 测试：完整树保管权一次推进、合成场景（根箱含 E1 与子箱内 E2，
 * 待交接时 E2 封条异常 → 整箱接收 422）、箱独立封条、幂等与权限顺序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ContainerTransferApiTest {

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

    private void intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        assertThat(postJson("/api/evidence", actor, body).getResponse().getStatus()).isEqualTo(201);
    }

    private void createBox(String actor, String containerKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("containerKey", containerKey);
        body.put("sealNo", "BOXSEAL-1");
        assertThat(postJson("/api/containers", actor, body).getResponse().getStatus()).isEqualTo(201);
    }

    private MvcResult pack(String actor, String requestId, String containerKey, int expectedVersion,
                           List<String> evidenceKeys, List<String> containerKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        body.put("evidenceKeys", evidenceKeys);
        body.put("containerKeys", containerKeys);
        return postJson("/api/containers/" + containerKey + "/members/add", actor, body);
    }

    private void pack(String actor, String containerKey, int expectedVersion,
                      List<String> evidenceKeys, List<String> containerKeys) throws Exception {
        assertThat(pack(actor, uniqueKey("REQ"), containerKey, expectedVersion,
                evidenceKeys, containerKeys).getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult initiateBox(String actor, String requestId, String containerKey,
                                  int expectedVersion, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("toCustodian", toCustodian);
        body.put("expectedVersion", expectedVersion);
        return postJson("/api/containers/" + containerKey + "/transfers", actor, body);
    }

    private MvcResult decideBox(String actor, String requestId, String containerKey,
                                String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        return postJson("/api/containers/" + containerKey + "/transfers/" + action, actor, body);
    }

    private MvcResult inspectEvidence(String actor, String evidenceKey, boolean passed)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson("/api/evidence/" + evidenceKey + "/seal-inspections", actor, body);
    }

    private MvcResult inspectBox(String actor, String containerKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("passed", passed);
        body.put("note", "box check");
        return postJson("/api/containers/" + containerKey + "/seal-inspections", actor, body);
    }

    private JsonNode tree(String containerKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/containers/{key}/tree", containerKey))
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

    /**
     * 合成树：根箱 root 含 E1 与子箱 child，child 内含 E2；返回各键。
     */
    private record SyntheticTree(String root, String child, String e1, String e2) {
    }

    private SyntheticTree syntheticTree(String actor) throws Exception {
        SyntheticTree keys = new SyntheticTree(uniqueKey("BOX"), uniqueKey("BOX"),
                uniqueKey("EV"), uniqueKey("EV"));
        createBox(actor, keys.root());
        createBox(actor, keys.child());
        intake(actor, keys.e1());
        intake(actor, keys.e2());
        pack(actor, keys.root(), 1, List.of(keys.e1()), List.of());
        pack(actor, keys.root(), 2, List.of(), List.of(keys.child()));
        pack(actor, keys.child(), 3, List.of(keys.e2()), List.of());
        return keys;
    }

    @Test
    void fullBoxTransferAdvancesAllCustodiansAtOnce() throws Exception {
        SyntheticTree keys = syntheticTree("alice");

        MvcResult initiated = initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "bob");
        assertThat(initiated.getResponse().getStatus()).isEqualTo(200);
        JsonNode transfer = objectMapper.readTree(initiated.getResponse().getContentAsString());
        assertThat(transfer.get("status").asText()).isEqualTo("PENDING");
        assertThat(transfer.get("toCustodian").asText()).isEqualTo("bob");

        MvcResult accepted = decideBox("bob", uniqueKey("REQ"), keys.root(), "accept");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode tree = objectMapper.readTree(accepted.getResponse().getContentAsString());
        // 整棵树保管权一次推进：根箱、子箱、E1、E2 全部归 bob，状态回到 SEALED
        assertThat(tree.get("container").get("custodianId").asText()).isEqualTo("bob");
        assertThat(tree.get("container").get("status").asText()).isEqualTo("SEALED");
        assertThat(tree.get("container").get("version").asInt()).isEqualTo(4);
        JsonNode child = tree.get("childContainers").get(0);
        assertThat(child.get("container").get("containerKey").asText()).isEqualTo(keys.child());
        assertThat(child.get("container").get("custodianId").asText()).isEqualTo("bob");
        assertThat(tree.get("evidenceItems").get(0).get("custodianId").asText()).isEqualTo("bob");
        assertThat(child.get("evidenceItems").get(0).get("custodianId").asText()).isEqualTo("bob");
        assertThat(custodyChain(keys.e1()).get("evidence").get("custodianId").asText())
                .isEqualTo("bob");
        assertThat(custodyChain(keys.e2()).get("evidence").get("custodianId").asText())
                .isEqualTo("bob");
    }

    @Test
    void brokenMemberSealBlocksWholeBoxAcceptAndKeepsFrozenTree() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        assertThat(initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "bob")
                .getResponse().getStatus()).isEqualTo(200);

        // 待交接期间允许原保管人检查成员封条：E2 封条异常登记
        assertThat(inspectEvidence("alice", keys.e2(), false).getResponse().getStatus())
                .isEqualTo(200);

        // 整箱接收 → 422，E1 保管人不改变
        assertThat(decideBox("bob", uniqueKey("REQ"), keys.root(), "accept")
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(custodyChain(keys.e1()).get("evidence").get("custodianId").asText())
                .isEqualTo("alice");

        // 冻结树仍列完整 E1 和 E2
        JsonNode tree = tree(keys.root());
        assertThat(tree.get("container").get("status").asText()).isEqualTo("TRANSFER_PENDING");
        assertThat(tree.get("container").get("version").asInt()).isEqualTo(4);
        assertThat(tree.get("evidenceItems")).hasSize(1);
        assertThat(tree.get("evidenceItems").get(0).get("evidenceKey").asText()).isEqualTo(keys.e1());
        JsonNode child = tree.get("childContainers").get(0);
        assertThat(child.get("container").get("containerKey").asText()).isEqualTo(keys.child());
        assertThat(child.get("evidenceItems")).hasSize(1);
        assertThat(child.get("evidenceItems").get(0).get("evidenceKey").asText()).isEqualTo(keys.e2());
        assertThat(child.get("evidenceItems").get(0).get("status").asText())
                .isEqualTo("SEAL_BROKEN");

        // 交接仍待接收：原保管人取消后子树解冻，保管权不变
        assertThat(decideBox("alice", uniqueKey("REQ"), keys.root(), "cancel")
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode unfrozen = tree(keys.root());
        assertThat(unfrozen.get("container").get("status").asText()).isEqualTo("SEALED");
        assertThat(unfrozen.get("container").get("custodianId").asText()).isEqualTo("alice");
    }

    @Test
    void acceptByNonReceiverAndCancelPermissionsFollowOriginalRules() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "bob");

        // 非指定接收人接受 → 409；接收人不能自行取消 → 409
        assertThat(decideBox("carol", uniqueKey("REQ"), keys.root(), "accept")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideBox("bob", uniqueKey("REQ"), keys.root(), "cancel")
                .getResponse().getStatus()).isEqualTo(409);
        // 原保管人取消 → 200
        assertThat(decideBox("alice", uniqueKey("REQ"), keys.root(), "cancel")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(keys.root()).get("container").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void initiateWithStaleVersionOrOnChildBoxRejected() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        assertThat(initiateBox("alice", uniqueKey("REQ"), keys.root(), 3, "bob")
                .getResponse().getStatus()).isEqualTo(409);
        // 子箱不能单独发起整箱交接
        assertThat(initiateBox("alice", uniqueKey("REQ"), keys.child(), 1, "bob")
                .getResponse().getStatus()).isEqualTo(409);
        // 接收人不能与发起人相同
        assertThat(initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "alice")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void brokenBoxSealBlocksInitiate() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        // 箱独立封条异常登记 → 根箱不可交接
        assertThat(inspectBox("alice", keys.root(), false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(tree(keys.root()).get("container").get("sealStatus").asText())
                .isEqualTo("BROKEN");
        assertThat(initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "bob")
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void intactBoxSealDoesNotImplyMembersIntact() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        // 成员 E2 封条异常（箱封条仍完好）→ 根箱不可交接
        assertThat(inspectEvidence("alice", keys.e2(), false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(tree(keys.root()).get("container").get("sealStatus").asText())
                .isEqualTo("INTACT");
        assertThat(initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "bob")
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void brokenChildBoxSealBlocksAccept() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        initiateBox("alice", uniqueKey("REQ"), keys.root(), 4, "bob");
        // 待交接期间子箱封条异常登记（异常登记与接收竞争只允许合法后态）
        assertThat(inspectBox("alice", keys.child(), false).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(decideBox("bob", uniqueKey("REQ"), keys.root(), "accept")
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(tree(keys.root()).get("container").get("custodianId").asText())
                .isEqualTo("alice");
    }

    @Test
    void transferIdempotentReplayReturnsFirstResponse() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        String initiateId = uniqueKey("REQ");
        MvcResult first = initiateBox("alice", initiateId, keys.root(), 4, "bob");
        MvcResult replay = initiateBox("alice", initiateId, keys.root(), 4, "bob");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        String acceptId = uniqueKey("REQ");
        MvcResult accepted = decideBox("bob", acceptId, keys.root(), "accept");
        MvcResult acceptedReplay = decideBox("bob", acceptId, keys.root(), "accept");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(acceptedReplay.getResponse().getStatus()).isEqualTo(200);
        assertThat(acceptedReplay.getResponse().getContentAsString())
                .isEqualTo(accepted.getResponse().getContentAsString());
        // 重放不重复推进：保管权只切换一次
        assertThat(tree(keys.root()).get("container").get("custodianId").asText()).isEqualTo("bob");

        // 同 requestId 改参 → 409
        assertThat(initiateBox("alice", initiateId, keys.root(), 4, "carol")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedRequestDoesNotConsumeRequestId() throws Exception {
        SyntheticTree keys = syntheticTree("alice");
        String requestId = uniqueKey("REQ");
        // 版本不匹配失败 → 409，不占键
        assertThat(pack("alice", requestId, keys.root(), 99, List.of(uniqueKey("EVX")), List.of())
                .getResponse().getStatus()).isEqualTo(409);
        String evidence = uniqueKey("EV");
        intake("alice", evidence);
        // 同 requestId 修正参数后成功
        assertThat(pack("alice", requestId, keys.root(), 4, List.of(evidence), List.of())
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(tree(keys.root()).get("container").get("version").asInt()).isEqualTo(5);
    }

    @Test
    void currentPermissionCheckedBeforeReplay() throws Exception {
        String box = uniqueKey("BOX");
        String evidence = uniqueKey("EV");
        createBox("alice", box);
        intake("alice", evidence);
        String packId = uniqueKey("REQ");
        assertThat(pack("alice", packId, box, 1, List.of(evidence), List.of())
                .getResponse().getStatus()).isEqualTo(200);

        // 整箱交接给 bob 后，alice 重放原装箱请求：当前权限先于重放 → 409 非保管人
        initiateBox("alice", uniqueKey("REQ"), box, 2, "bob");
        decideBox("bob", uniqueKey("REQ"), box, "accept");
        MvcResult replay = pack("alice", packId, box, 1, List.of(evidence), List.of());
        assertThat(replay.getResponse().getStatus()).isEqualTo(409);
        assertThat(replay.getResponse().getContentAsString()).contains("保管人");
    }
}
