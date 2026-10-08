package com.example.starter.baggage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

import com.example.starter.baggage.BaggageDtos.ManifestProofResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MERKLE 清单根摘要与成员证明测试：封舱字节序与根冻结、证明查询、
 * 补到证明核验的通过与各失败分支、操作者要求、普通航段兼容及幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageManifestProofTest {

    /** U+E000：UTF-8 首字节 0xEE；UTF-16 单元 0xE000。 */
    private static final String BAG_E000 = "BAG";
    /** U+1F600：UTF-8 首字节 0xF0；UTF-16 前导代理 0xD83D（自然序排在 U+E000 前）。 */
    private static final String BAG_EMOJI = "BAG😀";

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
    void merkleSeal_sortsByUtf8BytesAndFreezesRootLeafCountAndOrder() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE").andExpect(status().isCreated())
                .andExpect(jsonPath("$.legType").value("MERKLE"));
        registerBag("BAG-A", List.of("LEG_M"));
        registerBag(BAG_E000, List.of("LEG_M"));
        registerBag(BAG_EMOJI, List.of("LEG_M"));
        load("LEG_M", 1, List.of(BAG_EMOJI, "BAG-A", BAG_E000)).andExpect(status().isOk());

        // 自然 UTF-16 序会把 BAG😀 排在 BAG 前；封舱必须按 UTF-8 字节无符号序
        seal("LEG_M", 2, "op-seal").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.manifest", contains("BAG-A", BAG_E000, BAG_EMOJI)))
                .andExpect(jsonPath("$.leafCount").value(3));

        // 独立计算期望根：叶 = SHA-256(0x00 || 4 字节大端长度 || 袋号)，父 = SHA-256(0x01 || 左 || 右)
        String h1 = leafHex("BAG-A");
        String h2 = leafHex(BAG_E000);
        String h3 = leafHex(BAG_EMOJI);
        String expectedRoot = parentHex(parentHex(h1, h2), parentHex(h3, h3));

        mockMvc.perform(get("/api/legs/LEG_M/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestRoot").value(expectedRoot))
                .andExpect(jsonPath("$.leafCount").value(3))
                .andExpect(jsonPath("$.manifest", contains("BAG-A", BAG_E000, BAG_EMOJI)));
    }

    @Test
    void merkleSeal_emptyManifestRejectedAndOperatorRequired() throws Exception {
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE").andExpect(status().isCreated());
        // 空清单拒绝封舱
        seal("LEG_M", 1, "op-seal").andExpect(status().isUnprocessableEntity());
        // MERKLE 封舱必须携带非空操作者
        registerBag("BAG1", List.of("LEG_M"));
        load("LEG_M", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG_M", 2, null).andExpect(status().isBadRequest());
        seal("LEG_M", 2, "  ").andExpect(status().isBadRequest());
        // 携带操作者后成功，版本未被失败请求消耗
        seal("LEG_M", 2, "op-seal").andExpect(status().isOk())
                .andExpect(jsonPath("$.leafCount").value(1))
                .andExpect(jsonPath("$.manifestRoot").value(leafHex("BAG1")));
        // 普通航段空清单仍可封舱（兼容旧行为）
        registerLeg("LEG_S", "PEK", "SHA", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.legType").value("STANDARD"));
        seal("LEG_S", 1, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", hasSize(0)))
                .andExpect(jsonPath("$.manifestRoot").doesNotExist());
    }

    @Test
    void proofQuery_abcManifest_cBottomSiblingIsItself() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerBag("BAG-A", List.of("LEG1"));
        registerBag("BAG-B", List.of("LEG1"));
        registerBag("BAG-C", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG-A", "BAG-B", "BAG-C")).andExpect(status().isOk());
        seal("LEG1", 2, "op-seal").andExpect(status().isOk());
        String root = baggageService.getManifest("LEG1").manifestRoot();

        // C 为第 3 叶（下标 2），奇数叶复制末项：C 的底层兄弟是 C 自身
        mockMvc.perform(get("/api/legs/LEG1/manifest-proof").param("bagTag", "BAG-C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(2))
                .andExpect(jsonPath("$.leafCount").value(3))
                .andExpect(jsonPath("$.manifestRoot").value(root))
                .andExpect(jsonPath("$.proof", hasSize(2)))
                .andExpect(jsonPath("$.proof[0].side").value("RIGHT"))
                .andExpect(jsonPath("$.proof[0].digest").value(leafHex("BAG-C")))
                .andExpect(jsonPath("$.proof[1].side").value("LEFT"))
                .andExpect(jsonPath("$.proof[1].digest")
                        .value(parentHex(leafHex("BAG-A"), leafHex("BAG-B"))));

        // A 的证明：两层兄弟均在右侧，底层为 B 的叶摘要，上一层为 H(hC||hC)
        mockMvc.perform(get("/api/legs/LEG1/manifest-proof").param("bagTag", "BAG-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.proof[0].side").value("RIGHT"))
                .andExpect(jsonPath("$.proof[0].digest").value(leafHex("BAG-B")))
                .andExpect(jsonPath("$.proof[1].side").value("RIGHT"))
                .andExpect(jsonPath("$.proof[1].digest")
                        .value(parentHex(leafHex("BAG-C"), leafHex("BAG-C"))));
    }

    @Test
    void proofQuery_rejectsNonMerkleUnsealedUnknownBagAndLeg() throws Exception {
        registerLeg("LEG_S", "PEK", "SHA", null);
        registerLeg("LEG_M", "PEK", "SHA", "MERKLE");
        registerBag("BAG1", List.of("LEG_M"));
        // 普通航段无成员证明
        mockMvc.perform(get("/api/legs/LEG_S/manifest-proof").param("bagTag", "BAG1"))
                .andExpect(status().isUnprocessableEntity());
        // MERKLE 未封舱无成员证明
        mockMvc.perform(get("/api/legs/LEG_M/manifest-proof").param("bagTag", "BAG1"))
                .andExpect(status().isUnprocessableEntity());
        // 航段不存在
        mockMvc.perform(get("/api/legs/NOPE/manifest-proof").param("bagTag", "BAG1"))
                .andExpect(status().isNotFound());

        load("LEG_M", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG_M", 2, "op-seal").andExpect(status().isOk());
        // 清单外袋号
        mockMvc.perform(get("/api/legs/LEG_M/manifest-proof").param("bagTag", "BAG_X"))
                .andExpect(status().isNotFound());
    }

    @Test
    void recoverWithProof_cShortedThenRecoveredAndRootStaysFrozen() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerBag("BAG-A", List.of("LEG1"));
        registerBag("BAG-B", List.of("LEG1"));
        registerBag("BAG-C", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG-A", "BAG-B", "BAG-C")).andExpect(status().isOk());
        seal("LEG1", 2, "op-seal").andExpect(status().isOk());
        String sealRoot = baggageService.getManifest("LEG1").manifestRoot();

        // 差异到达：C 缺失转短卸；MERKLE 差异到达须携带操作者
        diffArrive("LEG1", 3, List.of("BAG-A", "BAG-B"), null).andExpect(status().isBadRequest());
        diffArrive("LEG1", 3, List.of("BAG-A", "BAG-B"), "op-arr").andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG-C")));
        // 短卸不改变冻结根
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.manifestRoot").value(sealRoot));

        // 用 C 的证明补到成功（单航段行程，补到即交付）
        ManifestProofResponse proof = baggageService.getManifestProof("LEG1", "BAG-C");
        recover("BAG-C", "LEG1", "SHA", "op-rec", proof.manifestRoot(), proof.leafIndex(),
                proofMaps(proof)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"));

        // 补到后根仍是封舱原根，证明查询不重新生成清单
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.manifestRoot").value(sealRoot))
                .andExpect(jsonPath("$.leafCount").value(3));
        mockMvc.perform(get("/api/legs/LEG1/manifest-proof").param("bagTag", "BAG-C"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manifestRoot").value(sealRoot));
    }

    @Test
    void recoverWithProof_anyMismatchIs422AndBagUnmoved() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerBag("BAG-A", List.of("LEG1"));
        registerBag("BAG-B", List.of("LEG1"));
        registerBag("BAG-C", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG-A", "BAG-B", "BAG-C")).andExpect(status().isOk());
        seal("LEG1", 2, "op-seal").andExpect(status().isOk());
        diffArrive("LEG1", 3, List.of("BAG-A", "BAG-B"), "op-arr").andExpect(status().isOk());

        ManifestProofResponse proofC = baggageService.getManifestProof("LEG1", "BAG-C");
        ManifestProofResponse proofA = baggageService.getManifestProof("LEG1", "BAG-A");
        String root = proofC.manifestRoot();
        List<Map<String, String>> cProof = proofMaps(proofC);

        // 用 A 的证明冒充 C -> 422
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, proofA.leafIndex(), proofMaps(proofA))
                .andExpect(status().isUnprocessableEntity());
        // 叶下标不符（C 的证明配 B 的下标）-> 422
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 1, cProof)
                .andExpect(status().isUnprocessableEntity());
        // 路径长度不符：少一层 / 多一层 -> 422
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 2, cProof.subList(0, 1))
                .andExpect(status().isUnprocessableEntity());
        List<Map<String, String>> longer = new java.util.ArrayList<>(cProof);
        longer.add(Map.of("side", "LEFT", "digest", leafHex("BAG-C")));
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 2, longer)
                .andExpect(status().isUnprocessableEntity());
        // 左右方向不符 -> 422
        List<Map<String, String>> flipped = new java.util.ArrayList<>(cProof);
        flipped.set(0, Map.of("side", "LEFT", "digest", cProof.get(0).get("digest")));
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 2, flipped)
                .andExpect(status().isUnprocessableEntity());
        // 清单根不符 -> 422
        recover("BAG-C", "LEG1", "SHA", "op-rec", leafHex("BAG-C"), 2, cProof)
                .andExpect(status().isUnprocessableEntity());
        // 篡改兄弟摘要 -> 422
        List<Map<String, String>> tampered = new java.util.ArrayList<>(cProof);
        tampered.set(0, Map.of("side", "RIGHT", "digest", leafHex("BAG-X")));
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 2, tampered)
                .andExpect(status().isUnprocessableEntity());
        // 缺少证明字段 -> 422
        recover("BAG-C", "LEG1", "SHA", "op-rec", null, null, null)
                .andExpect(status().isUnprocessableEntity());
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 2, null)
                .andExpect(status().isUnprocessableEntity());
        // 缺操作者 -> 400
        recover("BAG-C", "LEG1", "SHA", null, root, 2, cProof)
                .andExpect(status().isBadRequest());
        // 有效证明但实际站不符 -> 422
        recover("BAG-C", "LEG1", "CAN", "op-rec", root, 2, cProof)
                .andExpect(status().isUnprocessableEntity());

        // 全部失败均不移动行李：仍短卸在 PEK，无 RECOVERED 事件
        mockMvc.perform(get("/api/bags/BAG-C/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));
        Integer recoveredEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG-C' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(recoveredEvents).isZero();

        // 合法证明最终成功
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 2, cProof).andExpect(status().isOk());
    }

    @Test
    void singleLeafMerkle_emptyProofPathRecovers() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerBag("BAG1", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2, "op-seal").andExpect(status().isOk())
                .andExpect(jsonPath("$.leafCount").value(1))
                .andExpect(jsonPath("$.manifestRoot").value(leafHex("BAG1")));

        // 单叶树高 0，证明为空路径
        mockMvc.perform(get("/api/legs/LEG1/manifest-proof").param("bagTag", "BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leafIndex").value(0))
                .andExpect(jsonPath("$.proof", hasSize(0)));

        diffArrive("LEG1", 3, List.of(), "op-arr").andExpect(status().isOk());
        String root = baggageService.getManifest("LEG1").manifestRoot();
        // 多带证明层 -> 422；空路径 -> 200
        recover("BAG1", "LEG1", "SHA", "op-rec", root, 0,
                List.of(Map.of("side", "RIGHT", "digest", leafHex("BAG1"))))
                .andExpect(status().isUnprocessableEntity());
        recover("BAG1", "LEG1", "SHA", "op-rec", root, 0, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void standardLeg_recoverWithoutProofStillWorks() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG1", 2, null).andExpect(status().isOk());
        diffArrive("LEG1", 3, List.of(), null).andExpect(status().isOk());
        // 普通旧航段补到无需证明与操作者
        recover("BAG1", "LEG1", "SHA", null, null, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void recoverWithProof_idempotencyReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", "MERKLE");
        registerBag("BAG-C", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG-C")).andExpect(status().isOk());
        seal("LEG1", 2, "op-seal").andExpect(status().isOk());
        diffArrive("LEG1", 3, List.of(), "op-arr").andExpect(status().isOk());
        ManifestProofResponse proof = baggageService.getManifestProof("LEG1", "BAG-C");
        String root = proof.manifestRoot();
        List<Map<String, String>> cProof = proofMaps(proof);

        // 失败不占键：先以篡改证明失败（422），同键换正确证明成功
        String retryKey = UUID.randomUUID().toString();
        recoverWithKey(retryKey, "BAG-C", "LEG1", "SHA", "op-rec", root, 0,
                List.of()).andExpect(status().isOk());
        // 上面已成功，换行李重做一次完整流程验证失败不占键
        registerLeg("LEG2", "PEK", "SHA", "MERKLE");
        registerBag("BAG-D", List.of("LEG2"));
        load("LEG2", 1, List.of("BAG-D")).andExpect(status().isOk());
        seal("LEG2", 2, "op-seal").andExpect(status().isOk());
        diffArrive("LEG2", 3, List.of(), "op-arr").andExpect(status().isOk());
        ManifestProofResponse proofD = baggageService.getManifestProof("LEG2", "BAG-D");
        String failKey = UUID.randomUUID().toString();
        recoverWithKey(failKey, "BAG-D", "LEG2", "SHA", "op-rec", proofD.manifestRoot(), 1,
                List.of()).andExpect(status().isUnprocessableEntity());
        recoverWithKey(failKey, "BAG-D", "LEG2", "SHA", "op-rec", proofD.manifestRoot(), 0,
                proofMaps(proofD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));

        // 同键同参重放首次完整响应，事件与去重记录均只有一条
        recoverWithKey(retryKey, "BAG-C", "LEG1", "SHA", "op-rec", root, 0, cProof)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG-C' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(events).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, retryKey);
        assertThat(logs).isEqualTo(1);

        // 同键异参（改叶下标）-> 409
        recoverWithKey(retryKey, "BAG-C", "LEG1", "SHA", "op-rec", root, 1, cProof)
                .andExpect(status().isConflict());
        // 已补到后凭有效证明不得再次推进 -> 409
        recover("BAG-C", "LEG1", "SHA", "op-rec", root, 0, cProof)
                .andExpect(status().isConflict());
    }

    @Test
    void idempotency_sameKeyDifferentOperationReturns409() throws Exception {
        String key = UUID.randomUUID().toString();
        postJson("/api/legs", Map.of("requestId", key, "legId", "LEG1",
                "origin", "PEK", "destination", "SHA")).andExpect(status().isCreated());
        // 同键不同操作：结构化比较操作类型，返回 409
        postJson("/api/legs/LEG1/seal", Map.of("requestId", key, "expectedVersion", 1))
                .andExpect(status().isConflict());
        // 航段未被失败请求改变
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }


    private ResultActions registerLeg(String legId, String origin, String destination, String legType)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("legId", legId);
        body.put("origin", origin);
        body.put("destination", destination);
        if (legType != null) {
            body.put("legType", legType);
        }
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedVersion", expectedVersion);
        if (operator != null) {
            body.put("operator", operator);
        }
        return postJson("/api/legs/" + legId + "/seal", body);
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags,
                                     String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("expectedVersion", expectedVersion);
        body.put("bagTags", bagTags);
        if (operator != null) {
            body.put("operator", operator);
        }
        return postJson("/api/legs/" + legId + "/arrive-difference", body);
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation,
                                  String operator, String manifestRoot, Integer leafIndex,
                                  List<Map<String, String>> proof) throws Exception {
        return recoverWithKey(UUID.randomUUID().toString(), bagTag, missingLegId, actualStation,
                operator, manifestRoot, leafIndex, proof);
    }

    private ResultActions recoverWithKey(String requestId, String bagTag, String missingLegId,
                                         String actualStation, String operator, String manifestRoot,
                                         Integer leafIndex, List<Map<String, String>> proof) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("bagTag", bagTag);
        body.put("missingLegId", missingLegId);
        body.put("actualStation", actualStation);
        if (operator != null) {
            body.put("operator", operator);
        }
        if (manifestRoot != null) {
            body.put("manifestRoot", manifestRoot);
        }
        if (leafIndex != null) {
            body.put("leafIndex", leafIndex);
        }
        if (proof != null) {
            body.put("proof", proof);
        }
        return postJson("/api/bags/recover", body);
    }

    private static List<Map<String, String>> proofMaps(ManifestProofResponse response) {
        return response.proof().stream()
                .map(node -> Map.of("side", node.side(), "digest", node.digest()))
                .toList();
    }

    /** 独立叶摘要：SHA-256(0x00 || 4 字节大端袋号字节长度 || 袋号 UTF-8 字节)。 */
    private static String leafHex(String bagTag) throws Exception {
        byte[] data = bagTag.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 0);
        digest.update(ByteBuffer.allocate(4).putInt(data.length).array());
        return HexFormat.of().formatHex(digest.digest(data));
    }

    /** 独立父摘要：SHA-256(0x01 || 左 32 字节 || 右 32 字节)。 */
    private static String parentHex(String leftHex, String rightHex) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) 1);
        digest.update(HexFormat.of().parseHex(leftHex));
        return HexFormat.of().formatHex(digest.digest(HexFormat.of().parseHex(rightHex)));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
