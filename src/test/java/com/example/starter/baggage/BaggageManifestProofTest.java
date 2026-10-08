package com.example.starter.baggage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.baggage.BaggageDtos.ProofNode;
import com.example.starter.baggage.BaggageDtos.ProofResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新类型（MERKLE）航段清单根与成员证明测试：封舱字节序冻结、证明查询、
 * 补到证明核验（含 A 证明冒充 C）、根稳定性、幂等与并发边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageManifestProofTest {

    private static final String OPERATOR = "op-zhang";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    @Test
    void seal_merkleSortsByUtf8BytesFreezesRootAndValidatesOperator() throws Exception {
        // 新类型空清单拒绝封舱
        registerLeg("LEG_EMPTY", "PEK", "SHA", "MERKLE");
        seal("LEG_EMPTY", 1, OPERATOR).andExpect(status().isUnprocessableEntity());
        // 未知航段类型拒绝登记
        postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", "LEG_BAD", "origin", "PEK", "destination", "SHA", "legType", "UNKNOWN"))
                .andExpect(status().isUnprocessableEntity());

        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        registerBag("BAG豈", List.of("LEG_M"));
        registerBag("BAG😀", List.of("LEG_M"));
        load("LEG_M", 1, List.of("BAG豈", "BAG😀"));

        // 新类型封舱缺操作者 -> 422
        seal("LEG_M", 2, null).andExpect(status().isUnprocessableEntity());
        seal("LEG_M", 2, "  ").andExpect(status().isUnprocessableEntity());

        // 字符串次序为 [BAG😀, BAG豈]，UTF-8 字节无符号次序为 [BAG豈, BAG😀]
        List<String> expectedOrder = List.of("BAG豈", "BAG😀");
        String expectedRoot = MerkleTree.hex(MerkleTree.root(expectedOrder));
        seal("LEG_M", 2, OPERATOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("BAG豈", "BAG😀")))
                .andExpect(jsonPath("$.manifestRoot").value(expectedRoot))
                .andExpect(jsonPath("$.leafCount").value(2));

        mockMvc.perform(get("/api/legs/LEG_M/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legType").value("MERKLE"))
                .andExpect(jsonPath("$.manifest", contains("BAG豈", "BAG😀")))
                .andExpect(jsonPath("$.manifestRoot").value(expectedRoot))
                .andExpect(jsonPath("$.leafCount").value(2));

        // 操作者随去重记录落库
        String operator = jdbcTemplate.queryForObject(
                "SELECT operator FROM request_log WHERE operation = 'SEAL'", String.class);
        assertThat(operator).isEqualTo(OPERATOR);
    }

    @Test
    void proofQuery_returnsLeafIndexAndLevelSiblings() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        for (String tag : List.of("A", "B", "C")) {
            registerBag(tag, List.of("LEG_M"));
        }
        load("LEG_M", 1, List.of("A", "B", "C"));
        seal("LEG_M", 2, OPERATOR).andExpect(status().isOk());

        String hB = MerkleTree.hex(MerkleTree.leafHash("B"));
        String hC = MerkleTree.hex(MerkleTree.leafHash("C"));
        String pAB = MerkleTree.hex(MerkleTree.parentHash(
                MerkleTree.leafHash("A"), MerkleTree.leafHash("B")));
        String pCC = MerkleTree.hex(MerkleTree.parentHash(
                MerkleTree.leafHash("C"), MerkleTree.leafHash("C")));
        String root = MerkleTree.hex(MerkleTree.root(List.of("A", "B", "C")));

        // C 的底层兄弟是 C 自身（奇数叶复制末项）
        mockMvc.perform(get("/api/legs/LEG_M/proof/C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(2))
                .andExpect(jsonPath("$.leafCount").value(3))
                .andExpect(jsonPath("$.manifestRoot").value(root))
                .andExpect(jsonPath("$.proof", hasSize(2)))
                .andExpect(jsonPath("$.proof[0].position").value("RIGHT"))
                .andExpect(jsonPath("$.proof[0].hash").value(hC))
                .andExpect(jsonPath("$.proof[1].position").value("LEFT"))
                .andExpect(jsonPath("$.proof[1].hash").value(pAB));

        mockMvc.perform(get("/api/legs/LEG_M/proof/A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.proof[0].position").value("RIGHT"))
                .andExpect(jsonPath("$.proof[0].hash").value(hB))
                .andExpect(jsonPath("$.proof[1].position").value("RIGHT"))
                .andExpect(jsonPath("$.proof[1].hash").value(pCC));

        // 清单外袋号 -> 404；航段不存在 -> 404
        mockMvc.perform(get("/api/legs/LEG_M/proof/ZZ")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/NOPE/proof/A")).andExpect(status().isNotFound());
        // 普通旧航段不提供成员证明
        registerLeg("LEG_L", "PEK", "SHA", "LEGACY");
        registerBag("L1", List.of("LEG_L"));
        load("LEG_L", 1, List.of("L1"));
        seal("LEG_L", 2, null).andExpect(status().isOk());
        mockMvc.perform(get("/api/legs/LEG_L/proof/L1")).andExpect(status().isUnprocessableEntity());
        // 未封舱的新类型航段无冻结清单
        registerLeg("LEG_OPEN", "PEK", "SHA", "MERKLE");
        mockMvc.perform(get("/api/legs/LEG_OPEN/proof/A")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void merkleShortUnloadAndRecover_rootStableAndProofEnforced() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        for (String tag : List.of("A", "B", "C")) {
            registerBag(tag, List.of("LEG_M"));
        }
        load("LEG_M", 1, List.of("A", "B", "C"));
        seal("LEG_M", 2, OPERATOR).andExpect(status().isOk());
        String sealedRoot = currentRoot("LEG_M");
        assertThat(sealedRoot).isEqualTo(MerkleTree.hex(MerkleTree.root(List.of("A", "B", "C"))));

        // 新类型差异到达缺操作者 -> 422
        diffArrive("LEG_M", 3, List.of("A", "B"), null).andExpect(status().isUnprocessableEntity());
        // C 短卸
        diffArrive("LEG_M", 3, List.of("A", "B"), OPERATOR).andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", contains("C")));
        // 短卸后根不变
        assertThat(currentRoot("LEG_M")).isEqualTo(sealedRoot);

        // C 短卸后用其证明可补到
        ProofResponse proofC = baggageService.getManifestProof("LEG_M", "C");
        recoverWithProof("C", proofC, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"));
        // 补到后的根仍是封舱原根
        assertThat(currentRoot("LEG_M")).isEqualTo(sealedRoot);
        mockMvc.perform(get("/api/legs/LEG_M/manifest"))
                .andExpect(jsonPath("$.manifestRoot").value(sealedRoot))
                .andExpect(jsonPath("$.leafCount").value(3));
    }

    @Test
    void recover_proofMismatchReturns422AndBagNotMoved() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        for (String tag : List.of("A", "B", "C")) {
            registerBag(tag, List.of("LEG_M"));
        }
        load("LEG_M", 1, List.of("A", "B", "C"));
        seal("LEG_M", 2, OPERATOR).andExpect(status().isOk());
        diffArrive("LEG_M", 3, List.of("A", "B"), OPERATOR).andExpect(status().isOk());

        ProofResponse proofA = baggageService.getManifestProof("LEG_M", "A");
        ProofResponse proofC = baggageService.getManifestProof("LEG_M", "C");
        String root = proofC.manifestRoot();

        // 用 A 的证明冒充 C -> 422
        recoverWithProof("C", proofA, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity());
        // 正确证明配错叶下标 -> 422
        recoverRaw("C", root, 0, proofC.proof(), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity());
        // 路径长度与叶数不对应（截断一层） -> 422
        recoverRaw("C", root, 2, proofC.proof().subList(0, 1), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity());
        // 左右方向翻转 -> 422
        List<ProofNode> flipped = proofC.proof().stream()
                .map(node -> new ProofNode(
                        "LEFT".equals(node.position()) ? "RIGHT" : "LEFT", node.hash()))
                .toList();
        recoverRaw("C", root, 2, flipped, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity());
        // 清单根与冻结根不符 -> 422
        String wrongRoot = MerkleTree.hex(MerkleTree.root(List.of("A", "B")));
        recoverRaw("C", wrongRoot, 2, proofC.proof(), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity());
        // 缺清单根/叶下标/证明 -> 422
        postJson("/api/bags/recover", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", "C", "missingLegId", "LEG_M", "actualStation", "SHA",
                "operator", OPERATOR)).andExpect(status().isUnprocessableEntity());
        // 缺操作者 -> 422
        Map<String, Object> noOperator = new HashMap<>();
        noOperator.put("requestId", UUID.randomUUID().toString());
        noOperator.put("bagTag", "C");
        noOperator.put("missingLegId", "LEG_M");
        noOperator.put("actualStation", "SHA");
        noOperator.put("manifestRoot", root);
        noOperator.put("leafIndex", proofC.leafIndex());
        noOperator.put("proof", proofC.proof());
        postJson("/api/bags/recover", noOperator).andExpect(status().isUnprocessableEntity());

        // 任一不符均不移动行李：C 仍短卸在 PEK，无补到事件，根不变
        mockMvc.perform(get("/api/bags/C/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));
        Integer recoveredEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'C' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(recoveredEvents).isZero();
        assertThat(currentRoot("LEG_M")).isEqualTo(root);
    }

    @Test
    void recover_merkleIdempotencyReplayConflictAndFailureNotOccupying() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        for (String tag : List.of("A", "B", "C")) {
            registerBag(tag, List.of("LEG_M"));
        }
        load("LEG_M", 1, List.of("A", "B", "C"));
        seal("LEG_M", 2, OPERATOR).andExpect(status().isOk());
        // B、C 短卸
        diffArrive("LEG_M", 3, List.of("A"), OPERATOR).andExpect(status().isOk());

        ProofResponse proofB = baggageService.getManifestProof("LEG_M", "B");
        ProofResponse proofC = baggageService.getManifestProof("LEG_M", "C");

        // 同键同参（含证明全参数）重放首次完整响应
        String key = UUID.randomUUID().toString();
        recoverWithProof("B", proofB, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        recoverWithProof("B", proofB, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'B' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(events).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isEqualTo(1);

        // 同键异参（改叶下标） -> 409
        recoverRaw("B", proofB.manifestRoot(), 0, proofB.proof(), key)
                .andExpect(status().isConflict());

        // 失败不占键：C 先以错叶下标 422，再同键正确参数成功
        String retryKey = UUID.randomUUID().toString();
        recoverRaw("C", proofC.manifestRoot(), 0, proofC.proof(), retryKey)
                .andExpect(status().isUnprocessableEntity());
        recoverRaw("C", proofC.manifestRoot(), proofC.leafIndex(), proofC.proof(), retryKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void concurrentRecover_merkleProofAdvancesOnlyOnce() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        for (String tag : List.of("A", "B")) {
            registerBag(tag, List.of("LEG_M"));
        }
        load("LEG_M", 1, List.of("A", "B"));
        seal("LEG_M", 2, OPERATOR).andExpect(status().isOk());
        diffArrive("LEG_M", 3, List.of("A"), OPERATOR).andExpect(status().isOk());

        // 同一有效证明、不同 requestId 并发补到：只能推进一次
        ProofResponse proofB = baggageService.getManifestProof("LEG_M", "B");
        List<java.util.concurrent.Callable<Object>> tasks = List.of(
                () -> baggageService.recover(toRecoverRequest(proofB)),
                () -> baggageService.recover(toRecoverRequest(proofB)));
        List<Object> results = BaggageTestConcurrency.run(tasks);

        long successes = results.stream()
                .filter(com.example.starter.baggage.BaggageDtos.RecoverResponse.class::isInstance)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        mockMvc.perform(get("/api/bags/B/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'B' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(events).isEqualTo(1);
    }

    private com.example.starter.baggage.BaggageDtos.RecoverRequest toRecoverRequest(ProofResponse proof) {
        return new com.example.starter.baggage.BaggageDtos.RecoverRequest(
                UUID.randomUUID().toString(), proof.bagTag(), proof.legId(), "SHA",
                OPERATOR, proof.manifestRoot(), proof.leafIndex(), proof.proof());
    }

    private String currentRoot(String legId) {
        return jdbcTemplate.queryForObject(
                "SELECT manifest_root FROM leg WHERE leg_id = ?", String.class, legId);
    }

    private void registerLeg(String legId, String origin, String destination, String legType) {
        baggageService.registerLeg(new RegisterLegRequest(
                UUID.randomUUID().toString(), legId, origin, destination, legType));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion, String operator) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedVersion", expectedVersion);
        if (operator != null) {
            body.put("operator", operator);
        }
        return postJson("/api/legs/" + legId + "/seal", body);
    }

    private ResultActions diffArrive(String legId, int expectedVersion,
                                     List<String> bagTags, String operator) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedVersion", expectedVersion);
        body.put("bagTags", bagTags);
        if (operator != null) {
            body.put("operator", operator);
        }
        return postJson("/api/legs/" + legId + "/arrive-difference", body);
    }

    private ResultActions recoverWithProof(String bagTag, ProofResponse proof, String requestId)
            throws Exception {
        return recoverRaw(bagTag, proof.manifestRoot(), proof.leafIndex(), proof.proof(), requestId);
    }

    private ResultActions recoverRaw(String bagTag, String manifestRoot, int leafIndex,
                                     List<ProofNode> proof, String requestId) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", requestId, "bagTag", bagTag,
                "missingLegId", "LEG_M", "actualStation", "SHA", "operator", OPERATOR,
                "manifestRoot", manifestRoot, "leafIndex", leafIndex, "proof", proof));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}

