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
 * 数字摘要见证 API：主流程、422 失败分支、requestId 幂等/409、状态查询与历史导出固化测试。
 * 全部使用 H2 内存库（MODE=MySQL）真实建表与写入，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DigestWitnessApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String b64(String text) {
        return DigestService.encodeContentUtf8(text);
    }

    private void intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DOCUMENT");
        body.put("sealNo", "SEAL-1");
        MvcResult result = mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    private MvcResult seal(String actor, String evidenceKey, String commandKey,
                           String contentRef, String contentText) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("contentRef", contentRef);
        body.put("contentBase64", b64(contentText));
        return mockMvc.perform(post("/api/evidence/{key}/digest/seal", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult witness(String actor, String evidenceKey, String commandKey,
                              Integer versionNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        if (versionNo != null) {
            body.put("versionNo", versionNo);
        }
        return mockMvc.perform(post("/api/evidence/{key}/digest/witnesses", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult replaceContent(String actor, String evidenceKey, String commandKey,
                                     String contentRef, String contentText) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("contentRef", contentRef);
        body.put("contentBase64", b64(contentText));
        return mockMvc.perform(post("/api/evidence/{key}/digest/content", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult export(String actor, String requestId, List<String> evidenceKeys)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/evidence/exports")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode status(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/digest/status", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result);
    }

    /**
     * 封存 V1 -> 保管人甲、乙分别见证 -> 导出，验证完整主流程。
     */
    @Test
    void sealTwoWitnessesAndExportProducesPackage() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);

        MvcResult sealed = seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        assertThat(sealed.getResponse().getStatus()).isEqualTo(200);
        JsonNode version = json(sealed);
        assertThat(version.get("versionNo").asInt()).isEqualTo(1);
        String h = version.get("contentSha256").asText();
        assertThat(h).hasSize(64);

        assertThat(status(ev).get("state").asText()).isEqualTo("PENDING_WITNESS");

        assertThat(witness("alice", ev, uniqueKey("CMD"), null).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(status(ev).get("state").asText()).isEqualTo("PENDING_WITNESS");

        assertThat(witness("bob", ev, uniqueKey("CMD"), null).getResponse().getStatus())
                .isEqualTo(200);
        JsonNode ready = status(ev);
        assertThat(ready.get("state").asText()).isEqualTo("EXPORTABLE");
        assertThat(ready.get("versions").get(0).get("witnesses"))
                .containsExactly(objectMapper.valueToTree("alice"), objectMapper.valueToTree("bob"));

        String requestId = uniqueKey("REQ");
        MvcResult exported = export("alice", requestId, List.of(ev));
        assertThat(exported.getResponse().getStatus()).isEqualTo(200);
        JsonNode pkg = json(exported);
        assertThat(pkg.get("packageId").asText()).startsWith("PKG-");
        assertThat(pkg.get("requestId").asText()).isEqualTo(requestId);
        assertThat(pkg.get("items")).hasSize(1);
        JsonNode item = pkg.get("items").get(0);
        assertThat(item.get("evidenceKey").asText()).isEqualTo(ev);
        assertThat(item.get("versionNo").asInt()).isEqualTo(1);
        assertThat(item.get("contentSha256").asText()).isEqualTo(h);
        assertThat(item.get("witnesses"))
                .containsExactly(objectMapper.valueToTree("alice"), objectMapper.valueToTree("bob"));
    }

    @Test
    void sealByNonCustodianReturns409() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        assertThat(seal("mallory", ev, uniqueKey("CMD"), "ref://A", "content-A")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(status(ev).get("state").asText()).isEqualTo("NO_DIGEST");
    }

    @Test
    void sealOnMissingEvidenceReturns404() throws Exception {
        assertThat(seal("alice", uniqueKey("EV"), uniqueKey("CMD"), "ref://A", "content-A")
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void sealWithIllegalBase64Returns400() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("contentRef", "ref://A");
        body.put("contentBase64", "不是合法base64!!!");
        MvcResult result = mockMvc.perform(post("/api/evidence/{key}/digest/seal", ev)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void duplicateWitnessAndThirdWitnessReturn422() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        witness("alice", ev, uniqueKey("CMD"), null);
        witness("bob", ev, uniqueKey("CMD"), null);

        // 同一保管人重复见证
        MvcResult duplicate = witness("alice", ev, uniqueKey("CMD"), null);
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(422);
        // 已有两名不同见证人，第三人见证同样拒绝
        MvcResult third = witness("carol", ev, uniqueKey("CMD"), null);
        assertThat(third.getResponse().getStatus()).isEqualTo(422);

        JsonNode state = status(ev);
        assertThat(state.get("versions").get(0).get("witnesses")).hasSize(2);
    }

    @Test
    void exportWithoutSealOrWithSingleWitnessReturns422AndNoPackage() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);

        // 尚未封存
        assertThat(export("alice", uniqueKey("REQ"), List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);

        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        // 只有一名见证人
        witness("alice", ev, uniqueKey("CMD"), null);
        assertThat(export("alice", uniqueKey("REQ"), List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
        // 失败后不存在任何导出包
        assertThat(status(ev).get("exports")).isEmpty();
    }

    @Test
    void exportWithMissingEvidenceReturns404() throws Exception {
        assertThat(export("alice", uniqueKey("REQ"), List.of(uniqueKey("EV")))
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void tamperedContentCauses422AndRestoredContentExportsOriginalVersion() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        // 版本1 封存但不见证，随后重新封存产生版本2；保管人甲和乙见证版本2
        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A-v1");
        MvcResult sealedV2 = seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        assertThat(sealedV2.getResponse().getStatus()).isEqualTo(200);
        String h = json(sealedV2).get("contentSha256").asText();
        witness("alice", ev, uniqueKey("CMD"), 2);
        witness("bob", ev, uniqueKey("CMD"), 2);
        assertThat(status(ev).get("state").asText()).isEqualTo("EXPORTABLE");

        // 文件被改动：只替换当前内容，不重新封存
        assertThat(replaceContent("alice", ev, uniqueKey("CMD"), "ref://A", "content-A-tampered")
                .getResponse().getStatus()).isEqualTo(200);
        JsonNode mismatched = status(ev);
        assertThat(mismatched.get("state").asText()).isEqualTo("DIGEST_MISMATCH");
        assertThat(mismatched.get("currentContentSha256").asText()).isNotEqualTo(h);

        // 改动后导出 422，且不生成部分包
        String requestId = uniqueKey("REQ");
        assertThat(export("alice", requestId, List.of(ev)).getResponse().getStatus()).isEqualTo(422);
        assertThat(status(ev).get("exports")).isEmpty();

        // 恢复为与版本2 完全匹配的内容（引用与摘要均一致）
        replaceContent("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        assertThat(status(ev).get("state").asText()).isEqualTo("EXPORTABLE");

        MvcResult exported = export("alice", requestId, List.of(ev));
        assertThat(exported.getResponse().getStatus()).isEqualTo(200);
        JsonNode item = json(exported).get("items").get(0);
        // 恢复匹配内容后导出包清单仍标明版本2 与原摘要 H
        assertThat(item.get("versionNo").asInt()).isEqualTo(2);
        assertThat(item.get("contentSha256").asText()).isEqualTo(h);
    }

    @Test
    void witnessSupersededVersionReturns422AndOldWitnessesDoNotTransfer() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A-v1");
        witness("alice", ev, uniqueKey("CMD"), 1);
        witness("bob", ev, uniqueKey("CMD"), 1);

        // 重新封存产生版本2，版本1 失效；旧见证不转移给版本2
        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A-v2");
        JsonNode state = status(ev);
        assertThat(state.get("currentVersionNo").asInt()).isEqualTo(2);
        assertThat(state.get("state").asText()).isEqualTo("PENDING_WITNESS");
        assertThat(state.get("versions").get(0).get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(state.get("versions").get(0).get("witnesses")).hasSize(2);
        assertThat(state.get("versions").get(1).get("witnesses")).isEmpty();

        // 对已失效版本1 见证返回 422
        assertThat(witness("carol", ev, uniqueKey("CMD"), 1).getResponse().getStatus())
                .isEqualTo(422);
        // 新版本无两名见证，导出 422
        assertThat(export("alice", uniqueKey("REQ"), List.of(ev)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void witnessUnknownVersionReturns404() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        assertThat(witness("alice", ev, uniqueKey("CMD"), 99).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void sameRequestIdReplaysFirstPackageAndChangedSetReturns409() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        seal("alice", ev1, uniqueKey("CMD"), "ref://1", "content-1");
        seal("alice", ev2, uniqueKey("CMD"), "ref://2", "content-2");
        witness("alice", ev1, uniqueKey("CMD"), null);
        witness("bob", ev1, uniqueKey("CMD"), null);
        witness("alice", ev2, uniqueKey("CMD"), null);
        witness("bob", ev2, uniqueKey("CMD"), null);

        String requestId = uniqueKey("REQ");
        MvcResult first = export("alice", requestId, List.of(ev1, ev2));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstPkg = json(first);
        String packageId = firstPkg.get("packageId").asText();

        // 同 requestId 同集合重放：同包标识、同摘要清单
        MvcResult replay = export("alice", requestId, List.of(ev1, ev2));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 乱序同集合仍重放首次包
        MvcResult reordered = export("alice", requestId, List.of(ev2, ev1));
        assertThat(reordered.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(reordered).get("packageId").asText()).isEqualTo(packageId);

        // 集合内重复元素等价于去重后的同一集合
        MvcResult deduplicated = export("alice", requestId, List.of(ev1, ev2, ev1));
        assertThat(deduplicated.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(deduplicated).get("packageId").asText()).isEqualTo(packageId);

        // 同 requestId 换证物集合返回 409
        MvcResult changed = export("alice", requestId, List.of(ev1));
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);

        // 历史只生成了一个包
        assertThat(status(ev1).get("exports")).hasSize(1);
        assertThat(status(ev2).get("exports")).hasSize(1);
    }

    @Test
    void replayReturnsFirstPackageEvenAfterContentLaterTampered() throws Exception {
        String ev = uniqueKey("EV");
        intake("alice", ev);
        seal("alice", ev, uniqueKey("CMD"), "ref://A", "content-A");
        JsonNode v1 = status(ev);
        String h = v1.get("currentSha256").asText();
        witness("alice", ev, uniqueKey("CMD"), null);
        witness("bob", ev, uniqueKey("CMD"), null);

        String requestId = uniqueKey("REQ");
        MvcResult first = export("alice", requestId, List.of(ev));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String packageId = json(first).get("packageId").asText();

        // 导出后文件被改动
        replaceContent("alice", ev, uniqueKey("CMD"), "ref://A", "tampered-now");
        assertThat(status(ev).get("state").asText()).isEqualTo("DIGEST_MISMATCH");

        // 同 requestId 重放不重新裁决，原样返回首次包
        MvcResult replay = export("alice", requestId, List.of(ev));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(replay).get("packageId").asText()).isEqualTo(packageId);

        // 已导出记录不随后续内容变化改写
        MvcResult pkgResult = mockMvc.perform(get("/api/evidence/exports/{id}", packageId))
                .andReturn();
        assertThat(pkgResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode storedItem = json(pkgResult).get("items").get(0);
        assertThat(storedItem.get("versionNo").asInt()).isEqualTo(1);
        assertThat(storedItem.get("contentSha256").asText()).isEqualTo(h);
    }

    @Test
    void multiEvidencePackageFailsAtomicallyWhenAnyDigestMismatches() throws Exception {
        String ev1 = uniqueKey("EV");
        String ev2 = uniqueKey("EV");
        intake("alice", ev1);
        intake("alice", ev2);
        seal("alice", ev1, uniqueKey("CMD"), "ref://1", "content-1");
        seal("alice", ev2, uniqueKey("CMD"), "ref://2", "content-2");
        for (String ev : List.of(ev1, ev2)) {
            witness("alice", ev, uniqueKey("CMD"), null);
            witness("bob", ev, uniqueKey("CMD"), null);
        }
        // 第二件被改动
        replaceContent("alice", ev2, uniqueKey("CMD"), "ref://2", "content-2-tampered");

        assertThat(export("alice", uniqueKey("REQ"), List.of(ev1, ev2)).getResponse().getStatus())
                .isEqualTo(422);
        // 整体失败，两件证物都不得留下部分包
        assertThat(status(ev1).get("exports")).isEmpty();
        assertThat(status(ev2).get("exports")).isEmpty();

        // 恢复后两件同包成功，明细按证物键排序且各自标明版本与摘要
        replaceContent("alice", ev2, uniqueKey("CMD"), "ref://2", "content-2");
        List<String> sortedKeys = new java.util.ArrayList<>(new java.util.TreeSet<>(List.of(ev1, ev2)));
        MvcResult exported = export("alice", uniqueKey("REQ"), List.of(ev2, ev1));
        assertThat(exported.getResponse().getStatus()).isEqualTo(200);
        JsonNode items = json(exported).get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("evidenceKey").asText()).isEqualTo(sortedKeys.get(0));
        assertThat(items.get(1).get("evidenceKey").asText()).isEqualTo(sortedKeys.get(1));
        assertThat(items.get(0).get("versionNo").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("versionNo").asInt()).isEqualTo(1);
    }

    @Test
    void sealIdempotentReplayReturnsFirstVersion() throws Exception {
        String ev = uniqueKey("EV");
        String commandKey = uniqueKey("CMD");
        intake("alice", ev);
        MvcResult first = seal("alice", ev, commandKey, "ref://A", "content-A");
        MvcResult replay = seal("alice", ev, commandKey, "ref://A", "content-A");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不产生第二个版本
        assertThat(status(ev).get("versions")).hasSize(1);
    }

    @Test
    void statusOfMissingEvidenceReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/digest/status", uniqueKey("EV")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void getUnknownPackageReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/exports/{id}", uniqueKey("PKG")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }
}
