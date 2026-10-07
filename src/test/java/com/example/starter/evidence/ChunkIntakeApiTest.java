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

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 分块入库 API 主流程与失败分支测试（真实 H2 库）：
 * 乱序补块、重叠拒绝、等价 Base64、缺块/摘要错 422、放弃重建、权限与读字节归属。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkIntakeApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String B64_AB = "YWI=";
    private static final String B64_C = "Yw==";
    private static final String B64_ABC = "YWJj";
    private static final String B64_BC = "YmM=";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult createIntake(String actor, String commandKey, String intakeKey,
                                   String evidenceKey, int totalBytes, String sha256) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("intakeKey", intakeKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DIGITAL");
        body.put("sealNo", "SEAL-1");
        body.put("totalBytes", totalBytes);
        body.put("sha256", sha256);
        return mockMvc.perform(post("/api/chunk-intakes")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult append(String actor, String intakeKey, String commandKey,
                             int offset, String base64, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("offset", offset);
        body.put("dataBase64", base64);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/chunk-intakes/{key}/chunks", intakeKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decide(String actor, String intakeKey, String commandKey,
                             int expectedVersion, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/chunk-intakes/{key}/{action}", intakeKey, action)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode manifest(String intakeKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/chunk-intakes/{key}", intakeKey)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult readBytes(String actor, String evidenceKey) throws Exception {
        return mockMvc.perform(get("/api/evidence/{key}/bytes", evidenceKey)
                        .header(ACTOR_HEADER, actor))
                .andReturn();
    }

    @Test
    void createIntakeReturnsReceivingRecordAndStoresDigestLowercase() throws Exception {
        String intakeKey = uniqueKey("IN");
        MvcResult result = createIntake("alice", uniqueKey("CMD"), intakeKey,
                uniqueKey("EV"), 3, sha256Hex("abc").toUpperCase());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("intakeKey").asText()).isEqualTo(intakeKey);
        assertThat(body.get("creatorId").asText()).isEqualTo("alice");
        assertThat(body.get("status").asText()).isEqualTo("RECEIVING");
        assertThat(body.get("version").asInt()).isEqualTo(0);
        assertThat(body.get("receivedBytes").asInt()).isEqualTo(0);
        assertThat(body.get("totalBytes").asInt()).isEqualTo(3);
        assertThat(body.get("sha256").asText()).isEqualTo(sha256Hex("abc"));
    }

    @Test
    void createValidationFailuresReturn400() throws Exception {
        assertThat(createIntake("alice", uniqueKey("CMD"), uniqueKey("IN"), uniqueKey("EV"),
                0, sha256Hex("abc")).getResponse().getStatus()).isEqualTo(400);
        assertThat(createIntake("alice", uniqueKey("CMD"), uniqueKey("IN"), uniqueKey("EV"),
                65537, sha256Hex("abc")).getResponse().getStatus()).isEqualTo(400);
        assertThat(createIntake("alice", uniqueKey("CMD"), uniqueKey("IN"), uniqueKey("EV"),
                3, "not-a-digest").getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("intakeKey", uniqueKey("IN"));
        MvcResult result = mockMvc.perform(post("/api/chunk-intakes")
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void duplicateIntakeKeyOrReceivingEvidenceKeyReturns409() throws Exception {
        String intakeKey = uniqueKey("IN");
        String evidenceKey = uniqueKey("EV");
        assertThat(createIntake("alice", uniqueKey("CMD"), intakeKey, evidenceKey,
                3, sha256Hex("abc")).getResponse().getStatus()).isEqualTo(201);
        assertThat(createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"),
                3, sha256Hex("abc")).getResponse().getStatus()).isEqualTo(409);
        assertThat(createIntake("alice", uniqueKey("CMD"), uniqueKey("IN"), evidenceKey,
                3, sha256Hex("abc")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void outOfOrderChunksCompleteAbcAndSealEvidence() throws Exception {
        String intakeKey = uniqueKey("IN");
        String evidenceKey = uniqueKey("EV");
        createIntake("alice", uniqueKey("CMD"), intakeKey, evidenceKey, 3, sha256Hex("abc"));

        // 先收偏移 2 的 c，再收偏移 0 的 ab，可完成接收
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 2, B64_C, 0)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 1)
                .getResponse().getStatus()).isEqualTo(201);

        JsonNode manifest = manifest(intakeKey);
        assertThat(manifest.get("intake").get("receivedBytes").asInt()).isEqualTo(3);
        assertThat(manifest.get("intake").get("version").asInt()).isEqualTo(2);
        JsonNode chunks = manifest.get("chunks");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).get("offsetBytes").asInt()).isEqualTo(0);
        assertThat(chunks.get(1).get("offsetBytes").asInt()).isEqualTo(2);
        assertThat(chunks.get(0).has("data")).isFalse();

        MvcResult completed = decide("alice", intakeKey, uniqueKey("CMD"), 2, "complete");
        assertThat(completed.getResponse().getStatus()).isEqualTo(201);
        JsonNode evidence = objectMapper.readTree(completed.getResponse().getContentAsString());
        assertThat(evidence.get("evidenceKey").asText()).isEqualTo(evidenceKey);
        assertThat(evidence.get("status").asText()).isEqualTo("SEALED");
        assertThat(evidence.get("custodianId").asText()).isEqualTo("alice");

        MvcResult bytes = readBytes("alice", evidenceKey);
        assertThat(bytes.getResponse().getStatus()).isEqualTo(200);
        JsonNode bytesBody = objectMapper.readTree(bytes.getResponse().getContentAsString());
        assertThat(bytesBody.get("dataBase64").asText()).isEqualTo(B64_ABC);
        assertThat(bytesBody.get("totalBytes").asInt()).isEqualTo(3);
        assertThat(bytesBody.get("sha256").asText()).isEqualTo(sha256Hex("abc"));

        // 完成后块清单只读，新块 409
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 3)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void completeWithMissingChunkReturns422AndCreatesNoEvidence() throws Exception {
        String intakeKey = uniqueKey("IN");
        String evidenceKey = uniqueKey("EV");
        createIntake("alice", uniqueKey("CMD"), intakeKey, evidenceKey, 3, sha256Hex("abc"));
        // 只收 ab，缺偏移 2 的 c
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(201);

        assertThat(decide("alice", intakeKey, uniqueKey("CMD"), 1, "complete")
                .getResponse().getStatus()).isEqualTo(422);
        // 不生成 SEALED 证物与保管链首项，记录仍接收中
        MvcResult chain = mockMvc.perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn();
        assertThat(chain.getResponse().getStatus()).isEqualTo(404);
        assertThat(manifest(intakeKey).get("intake").get("status").asText()).isEqualTo("RECEIVING");
    }

    @Test
    void manifestOfMissingIntakeReturns404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/chunk-intakes/{key}", uniqueKey("IN")))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void overlappingChunkWithDifferentContentReturns409() throws Exception {
        String intakeKey = uniqueKey("IN");
        createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"), 3, sha256Hex("abc"));
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(201);

        // 偏移 1 再收 bc，与既有 ab 部分重叠
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 1, B64_BC, 1)
                .getResponse().getStatus()).isEqualTo(409);
        // 完全相同范围但内容不同，同样 409
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, "eHk=", 1)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(manifest(intakeKey).get("chunks")).hasSize(1);
    }

    @Test
    void identicalRangeSameContentReturnsExistingChunk() throws Exception {
        String intakeKey = uniqueKey("IN");
        createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"), 3, sha256Hex("abc"));
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(201);

        MvcResult again = append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 1);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        JsonNode chunk = objectMapper.readTree(again.getResponse().getContentAsString());
        assertThat(chunk.get("offsetBytes").asInt()).isEqualTo(0);
        assertThat(chunk.get("lengthBytes").asInt()).isEqualTo(2);

        JsonNode manifest = manifest(intakeKey);
        assertThat(manifest.get("chunks")).hasSize(1);
        // 相同内容重复提交不推进版本
        assertThat(manifest.get("intake").get("version").asInt()).isEqualTo(1);
    }

    @Test
    void equivalentBase64EncodingsTreatedAsSameContent() throws Exception {
        String intakeKey = uniqueKey("IN");
        createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"), 3, sha256Hex("abc"));
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(201);

        // 缺省填充与含空白的等价编码解码字节相同，返回已有块而非形成新内容
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, "YWI", 1)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, "Y W I =", 1)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(manifest(intakeKey).get("chunks")).hasSize(1);
    }

    @Test
    void invalidChunkRequestsReturn400() throws Exception {
        String intakeKey = uniqueKey("IN");
        createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"), 3, sha256Hex("abc"));

        // 非法 Base64
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, "####", 0)
                .getResponse().getStatus()).isEqualTo(400);
        // 解码后超过 4096 字节
        String tooLarge = Base64.getEncoder().encodeToString(new byte[4097]);
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, tooLarge, 0)
                .getResponse().getStatus()).isEqualTo(400);
        // 范围越过声明的总长度（偏移 2 + 长度 2 > 3）
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 2, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(400);
        // 负偏移
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), -1, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(manifest(intakeKey).get("chunks")).isEmpty();
    }

    @Test
    void wrongDigestCompleteReturns422ThenAbandonAndRecreate() throws Exception {
        String intakeKey = uniqueKey("IN");
        String evidenceKey = uniqueKey("EV");
        // 声明摘要与实际内容 abc 不一致
        createIntake("alice", uniqueKey("CMD"), intakeKey, evidenceKey, 3, sha256Hex("zzz"));
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_ABC, 0)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(decide("alice", intakeKey, uniqueKey("CMD"), 1, "complete")
                .getResponse().getStatus()).isEqualTo(422);

        // 摘要错可放弃该接收，放弃后新块 409
        MvcResult abandoned = decide("alice", intakeKey, uniqueKey("CMD"), 1, "abandon");
        assertThat(abandoned.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(abandoned.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("ABANDONED");
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_ABC, 2)
                .getResponse().getStatus()).isEqualTo(409);

        // 另建新记录复用同一证物键，可重新接收并完成
        String rebuilt = uniqueKey("IN");
        assertThat(createIntake("alice", uniqueKey("CMD"), rebuilt, evidenceKey,
                3, sha256Hex("abc")).getResponse().getStatus()).isEqualTo(201);
        assertThat(append("alice", rebuilt, uniqueKey("CMD"), 0, B64_ABC, 0)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(decide("alice", rebuilt, uniqueKey("CMD"), 1, "complete")
                .getResponse().getStatus()).isEqualTo(201);
        MvcResult bytes = readBytes("alice", evidenceKey);
        assertThat(objectMapper.readTree(bytes.getResponse().getContentAsString())
                .get("dataBase64").asText()).isEqualTo(B64_ABC);
    }

    @Test
    void nonCreatorOperationsReturn409() throws Exception {
        String intakeKey = uniqueKey("IN");
        createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"), 3, sha256Hex("abc"));

        assertThat(append("mallory", intakeKey, uniqueKey("CMD"), 0, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("mallory", intakeKey, uniqueKey("CMD"), 0, "complete")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("mallory", intakeKey, uniqueKey("CMD"), 0, "abandon")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(manifest(intakeKey).get("intake").get("status").asText()).isEqualTo("RECEIVING");
    }

    @Test
    void pendingIntakeNotOperableViaCustodyEndpoints() throws Exception {
        String evidenceKey = uniqueKey("EV");
        createIntake("alice", uniqueKey("CMD"), uniqueKey("IN"), evidenceKey, 3, sha256Hex("abc"));

        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("commandKey", uniqueKey("CMD"));
        transfer.put("toCustodian", "bob");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        Map<String, Object> inspection = new LinkedHashMap<>();
        inspection.put("commandKey", uniqueKey("CMD"));
        inspection.put("passed", true);
        assertThat(mockMvc.perform(post("/api/evidence/{key}/seal-inspections", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inspection)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("commandKey", uniqueKey("CMD"));
        loan.put("loanKey", uniqueKey("LN"));
        loan.put("borrowerId", "carol");
        loan.put("purpose", "analysis");
        loan.put("dueAt", "2099-01-01T00:00:00");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loan)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void expectedVersionMismatchReturns409() throws Exception {
        String intakeKey = uniqueKey("IN");
        createIntake("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"), 3, sha256Hex("abc"));

        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 5)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 0, B64_AB, 0)
                .getResponse().getStatus()).isEqualTo(201);
        // 版本已推进，旧期望版本被拒绝
        assertThat(append("alice", intakeKey, uniqueKey("CMD"), 2, B64_C, 0)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decide("alice", intakeKey, uniqueKey("CMD"), 0, "complete")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void readBytesRestrictedToCurrentCustodianAcrossCustodyFlow() throws Exception {
        String intakeKey = uniqueKey("IN");
        String evidenceKey = uniqueKey("EV");
        createIntake("alice", uniqueKey("CMD"), intakeKey, evidenceKey, 3, sha256Hex("abc"));
        append("alice", intakeKey, uniqueKey("CMD"), 0, B64_ABC, 0);
        decide("alice", intakeKey, uniqueKey("CMD"), 1, "complete");

        // 非保管人不能读取完整字节
        assertThat(readBytes("mallory", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(readBytes("alice", evidenceKey).getResponse().getStatus()).isEqualTo(200);

        // 待交接期间保管人不变，按原归属限制：alice 仍可读，bob 不可读
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("commandKey", uniqueKey("CMD"));
        transfer.put("toCustodian", "bob");
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(readBytes("alice", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        assertThat(readBytes("bob", evidenceKey).getResponse().getStatus()).isEqualTo(409);

        // 交接接受后保管人切换：bob 可读，alice 不可读
        Map<String, Object> accept = new LinkedHashMap<>();
        accept.put("commandKey", uniqueKey("CMD"));
        assertThat(mockMvc.perform(post("/api/evidence/{key}/transfers/accept", evidenceKey)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(accept)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(readBytes("bob", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        assertThat(readBytes("alice", evidenceKey).getResponse().getStatus()).isEqualTo(409);

        // 借出期间保管人不变，仍只有保管人可读
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("commandKey", uniqueKey("CMD"));
        loan.put("loanKey", uniqueKey("LN"));
        loan.put("borrowerId", "carol");
        loan.put("purpose", "analysis");
        loan.put("dueAt", LocalDateTime.now(ZoneOffset.UTC).plusHours(1).toString());
        assertThat(mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, "bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loan)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(readBytes("bob", evidenceKey).getResponse().getStatus()).isEqualTo(200);
        assertThat(readBytes("carol", evidenceKey).getResponse().getStatus()).isEqualTo(409);
    }
}
