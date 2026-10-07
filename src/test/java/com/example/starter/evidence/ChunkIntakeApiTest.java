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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 分块接收入口在真实 H2(MODE=MySQL) 上的主流程、失败分支与幂等测试。
 * 合成字节均为测试内构造，不依赖任何外部数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkIntakeApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String BASE = "/api/evidence/chunk-intakes";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String b64(String text) {
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private MvcResult create(String actor, String requestId, String intakeKey,
                             int totalLength, String sha) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("intakeKey", intakeKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DIGITAL");
        body.put("sealNo", "SEAL-1");
        body.put("totalLength", totalLength);
        body.put("sha256", sha);
        return mockMvc.perform(post(BASE)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult upload(String actor, String intakeKey, String requestId, int offset,
                             String dataBase64, Integer expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("offset", offset);
        body.put("dataBase64", dataBase64);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return mockMvc.perform(post(BASE + "/{key}/chunks", intakeKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult complete(String actor, String intakeKey, String requestId,
                               Integer expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        if (expectedVersion != null) {
            body.put("expectedVersion", expectedVersion);
        }
        return mockMvc.perform(post(BASE + "/{key}/complete", intakeKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult abandon(String actor, String intakeKey, String requestId,
                              String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("reason", reason);
        return mockMvc.perform(post(BASE + "/{key}/abandon", intakeKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void outOfOrderChunksForSyntheticAbcCompleteAndCreateSealedEvidence() throws Exception {
        String key = uniqueKey("CI");
        assertThat(create("alice", uniqueKey("REQ"), key, 3, sha256("abc"))
                .getResponse().getStatus()).isEqualTo(201);

        // 先收偏移 2 的 c，再收偏移 0 的 ab
        assertThat(upload("alice", key, uniqueKey("REQ"), 2, b64("c"), null)
                .getResponse().getStatus()).isEqualTo(200);
        MvcResult ab = upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), null);
        assertThat(ab.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ab).get("version").asInt()).isEqualTo(2);

        MvcResult completed = complete("alice", key, uniqueKey("REQ"), null);
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        JsonNode completion = json(completed);
        assertThat(completion.get("evidence").get("evidenceKey").asText()).isEqualTo(key);
        assertThat(completion.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(completion.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(completion.get("chunks")).hasSize(2);
        assertThat(completion.get("chunks").get(0).get("offset").asInt()).isZero();
        assertThat(completion.get("chunks").get(1).get("offset").asInt()).isEqualTo(2);

        // 完成后块清单只读：新块 409
        assertThat(upload("alice", key, uniqueKey("REQ"), 0, b64("x"), null)
                .getResponse().getStatus()).isEqualTo(409);

        // 保管链首项：证物存在，尚无交接/借出/核验记录
        MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", key))
                .andReturn();
        assertThat(chain.getResponse().getStatus()).isEqualTo(200);
        JsonNode chainBody = json(chain);
        assertThat(chainBody.get("evidence").get("status").asText()).isEqualTo("SEALED");
        assertThat(chainBody.get("transfers")).isEmpty();
        assertThat(chainBody.get("loans")).isEmpty();
        assertThat(chainBody.get("inspections")).isEmpty();

        // 当前保管人可读取完整字节
        MvcResult bytes = mockMvc.perform(get("/api/evidence/{key}/bytes", key)
                        .header(ACTOR_HEADER, "alice"))
                .andReturn();
        assertThat(bytes.getResponse().getStatus()).isEqualTo(200);
        assertThat(bytes.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    void completingWithOnlyAbReturns422AndCreatesNothing() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), null);

        MvcResult result = complete("alice", key, uniqueKey("REQ"), null);
        assertThat(result.getResponse().getStatus()).isEqualTo(422);

        // 未生成 SEALED 证物、保管链首项不存在
        assertThat(mockMvc.perform(get("/api/evidence/{key}/custody-chain", key))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        // 接收仍是 RECEIVING，已覆盖 2 字节
        MvcResult status = mockMvc.perform(get(BASE + "/{key}", key)).andReturn();
        JsonNode statusBody = json(status);
        assertThat(statusBody.get("status").asText()).isEqualTo("RECEIVING");
        assertThat(statusBody.get("receivedLength").asInt()).isEqualTo(2);
        assertThat(statusBody.get("version").asInt()).isEqualTo(1);
    }

    @Test
    void overlappingChunkAfterAbReturns409() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), null);

        // 偏移 1 再收 bc，与既有 ab 部分重叠
        assertThat(upload("alice", key, uniqueKey("REQ"), 1, b64("bc"), null)
                .getResponse().getStatus()).isEqualTo(409);
        // 重叠块未落库，版本仍为 1
        assertThat(json(mockMvc.perform(get(BASE + "/{key}", key)).andReturn())
                .get("version").asInt()).isEqualTo(1);
    }

    @Test
    void sameRangeSameContentReturnsExistingChunkEvenWithNewRequestId() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        MvcResult first = upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 完全相同范围同内容（不同 requestId、等价 Base64 写法）：返回已有块，版本不增
        MvcResult again = upload("alice", key, uniqueKey("REQ"), 0, "YWI=", null);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(again).get("version").asInt()).isEqualTo(1);

        // 同范围不同内容：409，块不可改
        assertThat(upload("alice", key, uniqueKey("REQ"), 0, b64("xy"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void digestMismatchReturns422ThenAbandonAndNewIntakeCanSeal() throws Exception {
        String wrongKey = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), wrongKey, 3, sha256("xyz"));
        upload("alice", wrongKey, uniqueKey("REQ"), 0, b64("abc"), null);

        MvcResult failed = complete("alice", wrongKey, uniqueKey("REQ"), null);
        assertThat(failed.getResponse().getStatus()).isEqualTo(422);
        // 摘要错误不产生证物
        assertThat(mockMvc.perform(get("/api/evidence/{key}/custody-chain", wrongKey))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        // 放弃后终态：补块、再完成均 409
        assertThat(abandon("alice", wrongKey, uniqueKey("REQ"), "digest mismatch")
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(upload("alice", wrongKey, uniqueKey("REQ"), 0, b64("ab"), null)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(complete("alice", wrongKey, uniqueKey("REQ"), null)
                .getResponse().getStatus()).isEqualTo(409);

        // 另建新记录（新键、正确摘要）可完成
        String goodKey = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), goodKey, 3, sha256("abc"));
        upload("alice", goodKey, uniqueKey("REQ"), 0, b64("abc"), null);
        assertThat(complete("alice", goodKey, uniqueKey("REQ"), null)
                .getResponse().getStatus()).isEqualTo(200);
        // 已放弃旧键不允许原入库入口复用
        Map<String, Object> directIntake = new LinkedHashMap<>();
        directIntake.put("commandKey", uniqueKey("CMD"));
        directIntake.put("evidenceKey", wrongKey);
        directIntake.put("caseKey", "CASE-1");
        directIntake.put("category", "DIGITAL");
        directIntake.put("sealNo", "SEAL-1");
        assertThat(mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(directIntake)))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void nonOwnerCannotOperateAndPermissionPrecedesReplay() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        String ownerRequestId = uniqueKey("REQ");
        MvcResult first = upload("alice", key, ownerRequestId, 0, b64("ab"), null);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);

        // 非入库人携带主人已成功的 requestId 与完全相同参数：权限先于重放，仍 409
        assertThat(upload("mallory", key, ownerRequestId, 0, b64("ab"), null)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(complete("mallory", key, uniqueKey("REQ"), null)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(abandon("mallory", key, uniqueKey("REQ"), null)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void replayReturnsFirstResponseAndChangedParamsReturn409() throws Exception {
        String key = uniqueKey("CI");
        String requestId = uniqueKey("REQ");
        assertThat(create("alice", requestId, key, 3, sha256("abc"))
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult replayed = create("alice", requestId, key, 3, sha256("abc"));
        assertThat(replayed.getResponse().getStatus()).isEqualTo(201);

        upload("alice", key, uniqueKey("REQ"), 0, b64("abc"), null);
        MvcResult firstComplete = complete("alice", key, requestId, null);
        // requestId 已被创建占用且参数不同：409（全局 requestId 不允许跨操作复用）
        assertThat(firstComplete.getResponse().getStatus()).isEqualTo(409);

        String completeId = uniqueKey("REQ");
        MvcResult ok = complete("alice", key, completeId, null);
        MvcResult replay = complete("alice", key, completeId, null);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(ok.getResponse().getContentAsString());
    }

    @Test
    void failedCompleteDoesNotOccupyRequestId() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        upload("alice", key, uniqueKey("REQ"), 2, b64("c"), null);
        String completeId = uniqueKey("REQ");
        // 缺块失败
        assertThat(complete("alice", key, completeId, null).getResponse().getStatus())
                .isEqualTo(422);
        // 同一 requestId 补齐后可成功（失败未占键），且业务与去重日志一起提交
        upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), null);
        MvcResult retry = complete("alice", key, completeId, null);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void invalidCreateAndChunkParamsRejected() throws Exception {
        String key = uniqueKey("CI");
        assertThat(create("alice", uniqueKey("REQ"), key, 0, sha256("abc"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create("alice", uniqueKey("REQ"), key, 65537, sha256("abc"))
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create("alice", uniqueKey("REQ"), key, 3, "abc")
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(create("alice", uniqueKey("REQ"), key, 3, sha256("abc"))
                .getResponse().getStatus()).isEqualTo(201);

        // 越过总长度：409
        assertThat(upload("alice", key, uniqueKey("REQ"), 2, b64("cc"), null)
                .getResponse().getStatus()).isEqualTo(409);
        // 负偏移：400；空 Base64：400；非法 Base64：400
        assertThat(upload("alice", key, uniqueKey("REQ"), -1, b64("a"), null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(upload("alice", key, uniqueKey("REQ"), 0, "", null)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(upload("alice", key, uniqueKey("REQ"), 0, "***", null)
                .getResponse().getStatus()).isEqualTo(400);
        // 缺操作者头：400
        assertThat(mockMvc.perform(post(BASE + "/{key}/chunks", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("requestId", uniqueKey("REQ"), "offset", 0,
                                        "dataBase64", b64("a")))))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void expectedVersionMismatchReturns409() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        assertThat(upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), 5)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(upload("alice", key, uniqueKey("REQ"), 0, b64("ab"), 0)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(complete("alice", key, uniqueKey("REQ"), 9)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void bytesReadFollowsCustodyAndPendingKeyRejectsOriginalEntries() throws Exception {
        String key = uniqueKey("CI");
        create("alice", uniqueKey("REQ"), key, 3, sha256("abc"));
        // 待接收对象不允许原交接/核验/借出入口（此时无原证物行）
        for (String path : new String[]{"/transfers", "/seal-inspections", "/loans"}) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commandKey", uniqueKey("CMD"));
            if (path.equals("/transfers")) {
                body.put("toCustodian", "bob");
            } else if (path.equals("/seal-inspections")) {
                body.put("passed", true);
                body.put("note", "n");
            } else {
                body.put("loanKey", uniqueKey("LN"));
                body.put("borrowerId", "bob");
                body.put("purpose", "p");
                body.put("dueAt", java.time.LocalDateTime.now().plusHours(1).toString());
            }
            int status = mockMvc.perform(post("/api/evidence/{key}" + path, key)
                            .header(ACTOR_HEADER, "alice")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isIn(404, 409);
        }

        upload("alice", key, uniqueKey("REQ"), 0, b64("abc"), null);
        complete("alice", key, uniqueKey("REQ"), null);
        // 非保管人不能读取完整字节
        assertThat(mockMvc.perform(get("/api/evidence/{key}/bytes", key).header(ACTOR_HEADER, "bob"))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        // 交接给 bob 后只有 bob 能读
        Map<String, Object> transfer = Map.of("commandKey", uniqueKey("CMD"), "toCustodian", "bob");
        mockMvc.perform(post("/api/evidence/{key}/transfers", key)
                .header(ACTOR_HEADER, "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(transfer))).andReturn();
        mockMvc.perform(post("/api/evidence/{key}/transfers/accept", key)
                .header(ACTOR_HEADER, "bob")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("commandKey", uniqueKey("CMD")))))
                .andReturn();
        assertThat(mockMvc.perform(get("/api/evidence/{key}/bytes", key).header(ACTOR_HEADER, "alice"))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        MvcResult bobBytes = mockMvc.perform(get("/api/evidence/{key}/bytes", key)
                .header(ACTOR_HEADER, "bob")).andReturn();
        assertThat(bobBytes.getResponse().getStatus()).isEqualTo(200);
        assertThat(bobBytes.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("abc");
    }
}
