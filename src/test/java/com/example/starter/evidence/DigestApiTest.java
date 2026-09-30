package com.example.starter.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 数字摘要见证主流程与失败分支测试（真实 H2 内存库，MODE=MySQL，自动建表）。
 * 覆盖：封存版本、双人见证、导出成包、摘要不符 422 不出部分包、
 * requestId 重放/换集合 409、版本失效、状态查询与历史导出清单。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DigestApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final Base64.Encoder base64 = Base64.getEncoder();

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String b64(String content) {
        return base64.encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult accept(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        return mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
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

    private MvcResult registerContent(String actor, String evidenceKey, String commandKey,
                                      String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("contentBase64", b64(content));
        return mockMvc.perform(post("/api/evidence/{key}/contents", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult registerRaw(String actor, String evidenceKey, String commandKey,
                                  String contentBase64) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("contentBase64", contentBase64);
        return mockMvc.perform(post("/api/evidence/{key}/contents", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult seal(String actor, String evidenceKey) throws Exception {
        return seal(actor, evidenceKey, uniqueKey("CMD"));
    }

    private MvcResult seal(String actor, String evidenceKey, String commandKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(post("/api/evidence/{key}/sealed-contents", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult witness(String actor, String evidenceKey, int versionNo) throws Exception {
        return witness(actor, evidenceKey, uniqueKey("CMD"), versionNo);
    }

    private MvcResult witness(String actor, String evidenceKey, String commandKey,
                              int versionNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("versionNo", versionNo);
        return mockMvc.perform(post("/api/evidence/{key}/digest-witnesses", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult export(String requestId, List<String> evidenceKeys) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("evidenceKeys", evidenceKeys);
        return mockMvc.perform(post("/api/evidence/export-packages")
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode digest(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/digest", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 构造题干主场景：证物内容封存为 H，经交接后由 bob 重封存至版本 2，
     * 保管人甲(alice)与乙(bob)分别见证版本 2，处于可导出状态。
     */
    private String prepareWitnessedVersionTwo(String content) throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerContent("alice", evidenceKey, uniqueKey("CMD"), content);
        assertThat(seal("alice", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        initiate("alice", evidenceKey, "bob");
        assertThat(accept("bob", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        MvcResult sealed = seal("bob", evidenceKey);
        assertThat(sealed.getResponse().getStatus()).isEqualTo(200);
        JsonNode sealedBody = objectMapper.readTree(sealed.getResponse().getContentAsString());
        assertThat(sealedBody.get("versionNo").asInt()).isEqualTo(2);
        assertThat(witness("alice", evidenceKey, 2).getResponse().getStatus()).isEqualTo(200);
        assertThat(witness("bob", evidenceKey, 2).getResponse().getStatus()).isEqualTo(200);
        return evidenceKey;
    }

    @Test
    void sealComputesSha256AndCreatesIncrementingVersions() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        MvcResult registered = registerContent("alice", evidenceKey, uniqueKey("CMD"), "doc-A");
        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        JsonNode contentView = objectMapper.readTree(registered.getResponse().getContentAsString());
        assertThat(contentView.get("contentSha256").asText()).isEqualTo(sha256Hex("doc-A"));

        MvcResult first = seal("alice", evidenceKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        JsonNode v1 = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.get("contentSha256").asText()).isEqualTo(sha256Hex("doc-A"));

        // 登记新内容后重新封存产生新版本；旧版本只追加保留
        registerContent("alice", evidenceKey, uniqueKey("CMD"), "doc-A-v2");
        MvcResult second = seal("alice", evidenceKey);
        JsonNode v2 = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        assertThat(v2.get("contentSha256").asText()).isEqualTo(sha256Hex("doc-A-v2"));
    }

    @Test
    void sealWithoutRegisteredContentReturns422() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(seal("alice", evidenceKey).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void registerWithInvalidBase64Returns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(registerRaw("alice", evidenceKey, uniqueKey("CMD"), "@@not-base64@@")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void digestStateTransitionsThroughPendingExportableAndMismatch() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        JsonNode before = digest(evidenceKey);
        assertThat(before.get("sealed").asBoolean()).isFalse();
        assertThat(before.get("digestState").asText()).isEqualTo("NOT_SEALED");

        registerContent("alice", evidenceKey, uniqueKey("CMD"), "doc-A");
        seal("alice", evidenceKey);
        JsonNode pending = digest(evidenceKey);
        assertThat(pending.get("digestState").asText()).isEqualTo("PENDING_WITNESS");
        assertThat(pending.get("versionNo").asInt()).isEqualTo(1);

        initiate("alice", evidenceKey, "bob");
        accept("bob", evidenceKey);
        seal("bob", evidenceKey);
        witness("alice", evidenceKey, 2);
        JsonNode stillPending = digest(evidenceKey);
        assertThat(stillPending.get("digestState").asText()).isEqualTo("PENDING_WITNESS");
        assertThat(stillPending.get("witnesses")).hasSize(1);

        witness("bob", evidenceKey, 2);
        JsonNode exportable = digest(evidenceKey);
        assertThat(exportable.get("digestState").asText()).isEqualTo("EXPORTABLE");
        assertThat(exportable.get("witnesses")).hasSize(2);

        // 文件被改动但未重新封存：摘要异常，版本仍为 2
        registerContent("bob", evidenceKey, uniqueKey("CMD"), "doc-A-tampered");
        JsonNode mismatch = digest(evidenceKey);
        assertThat(mismatch.get("digestState").asText()).isEqualTo("DIGEST_MISMATCH");
        assertThat(mismatch.get("versionNo").asInt()).isEqualTo(2);
        assertThat(mismatch.get("contentSha256").asText()).isEqualTo(sha256Hex("doc-A"));
        assertThat(mismatch.get("currentSha256").asText()).isEqualTo(sha256Hex("doc-A-tampered"));
    }

    @Test
    void fullFlowExportsPackageWithTwoWitnessesAndVersionTwo() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");
        String requestId = uniqueKey("REQ");

        MvcResult exported = export(requestId, List.of(evidenceKey));
        assertThat(exported.getResponse().getStatus()).isEqualTo(200);
        JsonNode pkg = objectMapper.readTree(exported.getResponse().getContentAsString());
        assertThat(pkg.get("packageKey").asText()).startsWith("pkg-");
        assertThat(pkg.get("items")).hasSize(1);
        assertThat(pkg.get("items").get(0).get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(pkg.get("items").get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(pkg.get("items").get(0).get("contentSha256").asText())
                .isEqualTo(sha256Hex("doc-A"));
    }

    @Test
    void tamperedContentReturns422AndCreatesNoPackageThenRestoreKeepsVersionTwo() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");
        String firstRequest = uniqueKey("REQ");
        JsonNode firstPkg = objectMapper.readTree(
                export(firstRequest, List.of(evidenceKey)).getResponse().getContentAsString());
        String firstPackageKey = firstPkg.get("packageKey").asText();

        // 文件被改动：导出 422，且不生成部分包
        registerContent("bob", evidenceKey, uniqueKey("CMD"), "doc-A-tampered");
        String blockedRequest = uniqueKey("REQ");
        assertThat(export(blockedRequest, List.of(evidenceKey)).getResponse().getStatus())
                .isEqualTo(422);
        Integer blockedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package WHERE request_id = ?", Integer.class, blockedRequest);
        assertThat(blockedRows).isZero();
        Integer blockedItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package_item i JOIN export_package p ON p.id = i.package_id "
                        + "WHERE p.request_id = ?", Integer.class, blockedRequest);
        assertThat(blockedItems).isZero();

        // 恢复匹配内容（不重新封存）：导出成功，清单仍标明版本 2
        registerContent("bob", evidenceKey, uniqueKey("CMD"), "doc-A");
        String retryRequest = uniqueKey("REQ");
        MvcResult retried = export(retryRequest, List.of(evidenceKey));
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
        JsonNode retryPkg = objectMapper.readTree(retried.getResponse().getContentAsString());
        assertThat(retryPkg.get("items").get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(retryPkg.get("items").get(0).get("contentSha256").asText())
                .isEqualTo(sha256Hex("doc-A"));
        // 恢复后产生的是新包，首次包标识不变
        assertThat(retryPkg.get("packageKey").asText()).isNotEqualTo(firstPackageKey);

        // 历史导出清单保留两个成功包，被拒绝的请求无包
        JsonNode view = digest(evidenceKey);
        assertThat(view.get("exportPackages")).hasSize(2);
        assertThat(view.get("digestState").asText()).isEqualTo("EXPORTABLE");
    }

    @Test
    void duplicateWitnessReturns409() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");
        assertThat(witness("bob", evidenceKey, 2).getResponse().getStatus()).isEqualTo(409);
        assertThat(digest(evidenceKey).get("witnesses")).hasSize(2);
    }

    @Test
    void nonCustodianWitnessReturns409() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");
        // mallory 从未保管过该证物
        assertThat(witness("mallory", evidenceKey, 2).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void witnessStaleVersionReturns422AndOldWitnessesDoNotTransfer() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");

        // 版本 3 重新封存（内容不变也产生新版本）
        MvcResult sealed = seal("bob", evidenceKey);
        assertThat(sealed.getResponse().getStatus()).isEqualTo(200);
        JsonNode v3 = objectMapper.readTree(sealed.getResponse().getContentAsString());
        assertThat(v3.get("versionNo").asInt()).isEqualTo(3);

        // 旧版本 2 的见证不能转给新版本：状态回到待见证
        JsonNode view = digest(evidenceKey);
        assertThat(view.get("digestState").asText()).isEqualTo("PENDING_WITNESS");
        assertThat(view.get("versionNo").asInt()).isEqualTo(3);

        // 见证已失效的版本 2 返回 422
        assertThat(witness("bob", evidenceKey, 2).getResponse().getStatus()).isEqualTo(422);

        // 新版本见证不足导出 422
        assertThat(export(uniqueKey("REQ"), List.of(evidenceKey)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void exportWithInsufficientWitnessesReturns422WithoutPartialPackage() throws Exception {
        String ready = prepareWitnessedVersionTwo("doc-A");
        String incomplete = uniqueKey("EV");
        intake("bob", incomplete);
        registerContent("bob", incomplete, uniqueKey("CMD"), "doc-B");
        seal("bob", incomplete);
        witness("bob", incomplete, 1);

        String requestId = uniqueKey("REQ");
        // 包内一件见证齐备、一件仅一人见证：整体 422，不产生任何包与条目
        assertThat(export(requestId, List.of(ready, incomplete)).getResponse().getStatus())
                .isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package WHERE request_id = ?", Integer.class, requestId))
                .isZero();
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package_item i JOIN export_package p ON p.id = i.package_id "
                        + "WHERE p.request_id = ?", Integer.class, requestId);
        assertThat(itemCount).isZero();
    }

    @Test
    void exportBeforeSealReturns422() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerContent("alice", evidenceKey, uniqueKey("CMD"), "doc-A");
        assertThat(export(uniqueKey("REQ"), List.of(evidenceKey)).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void exportUnknownEvidenceReturns404AndEmptyKeysReturns400() throws Exception {
        assertThat(export(uniqueKey("REQ"), List.of(uniqueKey("EV"))).getResponse().getStatus())
                .isEqualTo(404);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", uniqueKey("REQ"));
        body.put("evidenceKeys", List.of());
        MvcResult empty = mockMvc.perform(post("/api/evidence/export-packages")
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void sameRequestIdReplaysFirstPackageAndChangedSetReturns409() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");
        String requestId = uniqueKey("REQ");

        MvcResult first = export(requestId, List.of(evidenceKey));
        MvcResult replay = export(requestId, List.of(evidenceKey));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM export_package WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);

        String other = uniqueKey("EV");
        intake("bob", other);
        registerContent("bob", other, uniqueKey("CMD"), "doc-other");
        seal("bob", other);
        witness("bob", other, 1);
        // 同一 requestId 更换证物集合返回 409
        assertThat(export(requestId, List.of(evidenceKey, other)).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void exportedPackageSurvivesLaterContentChange() throws Exception {
        String evidenceKey = prepareWitnessedVersionTwo("doc-A");
        String requestId = uniqueKey("REQ");
        MvcResult first = export(requestId, List.of(evidenceKey));
        JsonNode firstPkg = objectMapper.readTree(first.getResponse().getContentAsString());
        String firstSnapshot = firstPkg.get("items").get(0).get("contentSha256").asText();

        // 导出成功后改动内容：摘要异常，重放 requestId 仍返回首次包的标识与摘要清单
        registerContent("bob", evidenceKey, uniqueKey("CMD"), "doc-A-tampered");
        assertThat(digest(evidenceKey).get("digestState").asText()).isEqualTo("DIGEST_MISMATCH");
        MvcResult replay = export(requestId, List.of(evidenceKey));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        JsonNode replayed = objectMapper.readTree(replay.getResponse().getContentAsString());
        assertThat(replayed.get("packageKey").asText()).isEqualTo(firstPkg.get("packageKey").asText());
        assertThat(replayed.get("items").get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(replayed.get("items").get(0).get("contentSha256").asText()).isEqualTo(firstSnapshot);
    }

    @Test
    void sealAndContentOperationsEnforceCustodianAndEvidenceState() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerContent("alice", evidenceKey, uniqueKey("CMD"), "doc-A");

        // 非当前保管人不能登记内容或封存
        assertThat(registerContent("mallory", evidenceKey, uniqueKey("CMD"), "x")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(seal("mallory", evidenceKey).getResponse().getStatus()).isEqualTo(409);

        // 待接收交接期间禁止封存登记
        initiate("alice", evidenceKey, "bob");
        assertThat(seal("alice", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        accept("bob", evidenceKey);

        // 封条异常后禁止登记内容与封存，返回 422
        assertThat(inspect("bob", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(registerContent("bob", evidenceKey, uniqueKey("CMD"), "x")
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(seal("bob", evidenceKey).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void sealCommandIdempotentReplayReturnsFirstResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        registerContent("alice", evidenceKey, uniqueKey("CMD"), "doc-A");
        String commandKey = uniqueKey("CMD");
        MvcResult first = seal("alice", evidenceKey, commandKey);
        MvcResult replay = seal("alice", evidenceKey, commandKey);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不得产生第二个版本
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_content WHERE evidence_key = ?",
                Integer.class, evidenceKey)).isEqualTo(1);

        // 同键改参（用于内容登记）返回 409
        assertThat(registerContent("alice", evidenceKey, commandKey, "doc-A")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void digestOfUnknownEvidenceReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/digest", uniqueKey("EV")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }
}
