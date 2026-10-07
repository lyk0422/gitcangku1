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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 分块入库幂等与去重测试（真实 H2 库）：同键同参重放返回首次完整响应，同键改参 409，
 * 失败不占键，业务与去重原子提交，当前权限先于重放，文本参数原样比较。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChunkIntakeIdempotencyTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

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

    private Map<String, Object> createBody(String commandKey, String intakeKey, String evidenceKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("intakeKey", intakeKey);
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DIGITAL");
        body.put("sealNo", "SEAL-1");
        body.put("totalBytes", 3);
        body.put("sha256", sha256Hex("abc"));
        return body;
    }

    private MvcResult create(String actor, String commandKey, String intakeKey,
                             String evidenceKey) throws Exception {
        return mockMvc.perform(post("/api/chunk-intakes")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createBody(commandKey, intakeKey, evidenceKey))))
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

    private MvcResult complete(String actor, String intakeKey, String commandKey,
                               int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/chunk-intakes/{key}/complete", intakeKey)
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

    @Test
    void createReplayReturnsFirstResponseAndParamChangeReturns409() throws Exception {
        String commandKey = uniqueKey("CMD");
        String intakeKey = uniqueKey("IN");
        MvcResult first = create("alice", commandKey, intakeKey, uniqueKey("EV"));
        MvcResult replay = create("alice", commandKey, intakeKey,
                objectMapper.readTree(first.getResponse().getContentAsString())
                        .get("evidenceKey").asText());

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());

        // 同键改参 409
        assertThat(create("alice", commandKey, uniqueKey("IN"), uniqueKey("EV"))
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void appendReplayReturnsFirstResponseWithoutDuplicatingChunk() throws Exception {
        String intakeKey = uniqueKey("IN");
        create("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"));
        String commandKey = uniqueKey("CMD");

        MvcResult first = append("alice", intakeKey, commandKey, 0, "YWI=", 0);
        MvcResult replay = append("alice", intakeKey, commandKey, 0, "YWI=", 0);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(manifest(intakeKey).get("chunks")).hasSize(1);
    }

    @Test
    void completeReplayReturnsFirstResponse() throws Exception {
        String intakeKey = uniqueKey("IN");
        String evidenceKey = uniqueKey("EV");
        create("alice", uniqueKey("CMD"), intakeKey, evidenceKey);
        append("alice", intakeKey, uniqueKey("CMD"), 0, "YWJj", 0);
        String commandKey = uniqueKey("CMD");

        MvcResult first = complete("alice", intakeKey, commandKey, 1);
        MvcResult replay = complete("alice", intakeKey, commandKey, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(manifest(intakeKey).get("intake").get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void failedRequestDoesNotOccupyCommandKey() throws Exception {
        String intakeKey = uniqueKey("IN");
        create("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"));
        append("alice", intakeKey, uniqueKey("CMD"), 0, "YWI=", 0);

        // 重叠失败（409）不占键：同一命令键随后可用于合法请求
        String appendKey = uniqueKey("CMD");
        assertThat(append("alice", intakeKey, appendKey, 1, "YmM=", 1)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(append("alice", intakeKey, appendKey, 2, "Yw==", 1)
                .getResponse().getStatus()).isEqualTo(201);

        // 缺块完成失败（422）不占键：补齐后同一命令键可完成
        String completeKey = uniqueKey("CMD");
        String other = uniqueKey("IN");
        String otherEvidence = uniqueKey("EV");
        create("alice", uniqueKey("CMD"), other, otherEvidence);
        append("alice", other, uniqueKey("CMD"), 0, "YWI=", 0);
        assertThat(complete("alice", other, completeKey, 1)
                .getResponse().getStatus()).isEqualTo(422);
        append("alice", other, uniqueKey("CMD"), 2, "Yw==", 1);
        assertThat(complete("alice", other, completeKey, 2)
                .getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void permissionCheckedBeforeReplay() throws Exception {
        String intakeKey = uniqueKey("IN");
        create("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"));
        append("alice", intakeKey, uniqueKey("CMD"), 0, "YWJj", 0);
        String completeKey = uniqueKey("CMD");
        assertThat(complete("alice", intakeKey, completeKey, 1)
                .getResponse().getStatus()).isEqualTo(201);

        // 非入库人持相同命令键与参数：先按当前权限拒绝，而非重放首次成功
        MvcResult result = complete("mallory", intakeKey, completeKey, 1);
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("仅入库人");
    }

    @Test
    void textParamsComparedAsIsForSameCommandKey() throws Exception {
        String intakeKey = uniqueKey("IN");
        create("alice", uniqueKey("CMD"), intakeKey, uniqueKey("EV"));
        String commandKey = uniqueKey("CMD");
        assertThat(append("alice", intakeKey, commandKey, 0, "YWI=", 0)
                .getResponse().getStatus()).isEqualTo(201);

        // 等价 Base64 但文本不同：指纹按文本原样比较，同键视为改参 409
        MvcResult changed = append("alice", intakeKey, commandKey, 0, "YWI", 0);
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(changed.getResponse().getContentAsString()).contains("幂等键");
    }
}
