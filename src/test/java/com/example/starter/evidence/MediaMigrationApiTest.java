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
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 介质迁移 API 主流程与失败分支测试：登记介质、创建迁移、见证验证、切换、取消、
 * 封条异常阻断、迁移期间借出/交接拦截、幂等与期望版本边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaMigrationApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /** 合成内容的 SHA-256（小写十六进制）。 */
    private String sha256Of(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult postJson(String actor, String url, Map<String, Object> body)
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
        body.put("category", "DIGITAL");
        body.put("sealNo", "SEAL-1");
        return postJson(actor, "/api/evidence", body);
    }

    private MvcResult registerMedia(String actor, String evidenceKey, String commandKey,
                                    String mediaNo, String sha256) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("mediaNo", mediaNo);
        body.put("sha256", sha256);
        return postJson(actor, "/api/evidence/" + evidenceKey + "/media", body);
    }

    private MvcResult createMigration(String actor, String evidenceKey, String commandKey,
                                      String migrationKey, String targetMediaNo,
                                      String targetSha256) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("migrationKey", migrationKey);
        body.put("targetMediaNo", targetMediaNo);
        body.put("targetSha256", targetSha256);
        return postJson(actor, "/api/evidence/" + evidenceKey + "/migrations", body);
    }

    private MvcResult verify(String actor, String evidenceKey, String migrationKey,
                             String commandKey, String digest, long expectedVersion)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("digest", digest);
        body.put("expectedVersion", expectedVersion);
        return postJson(actor,
                "/api/evidence/" + evidenceKey + "/migrations/" + migrationKey + "/verify", body);
    }

    private MvcResult decideMigration(String actor, String evidenceKey, String migrationKey,
                                      String commandKey, long expectedVersion, String action)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("expectedVersion", expectedVersion);
        return postJson(actor,
                "/api/evidence/" + evidenceKey + "/migrations/" + migrationKey + "/" + action,
                body);
    }

    private MvcResult inspect(String actor, String evidenceKey, boolean passed) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("passed", passed);
        body.put("note", "routine");
        return postJson(actor, "/api/evidence/" + evidenceKey + "/seal-inspections", body);
    }

    private MvcResult borrow(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", "borrower-1");
        body.put("purpose", "鉴定用");
        body.put("dueAt", LocalDateTime.now().plusHours(1).toString());
        return postJson(actor, "/api/evidence/" + evidenceKey + "/loans", body);
    }

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return postJson(actor, "/api/evidence/" + evidenceKey + "/transfers", body);
    }

    private JsonNode listMedia(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/media", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult currentMedia(String evidenceKey) throws Exception {
        return mockMvc.perform(get("/api/evidence/{key}/media/current", evidenceKey)).andReturn();
    }

    private JsonNode listMigrations(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/migrations", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 构造一件 SEALED 且已登记 ACTIVE 介质的证物，返回源摘要。 */
    private String sealedWithMedia(String actor, String evidenceKey, String mediaNo)
            throws Exception {
        assertThat(intake(actor, evidenceKey).getResponse().getStatus()).isEqualTo(201);
        String sourceSha = sha256Of("content-" + evidenceKey);
        assertThat(registerMedia(actor, evidenceKey, uniqueKey("CMD"), mediaNo, sourceSha)
                .getResponse().getStatus()).isEqualTo(201);
        return sourceSha;
    }

    @Test
    void registerMediaCreatesActiveMediaAsCurrentSource() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String sha = sha256Of("content-a");

        MvcResult result = registerMedia("alice", evidenceKey, uniqueKey("CMD"), "DISK-1", sha);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode media = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(media.get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(media.get("sha256").asText()).isEqualTo(sha);
        assertThat(media.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(media.get("version").asInt()).isEqualTo(1);

        MvcResult current = currentMedia(evidenceKey);
        assertThat(current.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(current.getResponse().getContentAsString())
                .get("mediaNo").asText()).isEqualTo("DISK-1");
    }

    @Test
    void registerMediaByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(registerMedia("mallory", evidenceKey, uniqueKey("CMD"), "DISK-1",
                sha256Of("x")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerSecondActiveMediaReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        sealedWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(registerMedia("alice", evidenceKey, uniqueKey("CMD"), "DISK-2",
                sha256Of("y")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerMediaIdempotentReplayAndDifferentParams() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String commandKey = uniqueKey("CMD");
        String sha = sha256Of("content-a");
        MvcResult first = registerMedia("alice", evidenceKey, commandKey, "DISK-1", sha);
        MvcResult replay = registerMedia("alice", evidenceKey, commandKey, "DISK-1", sha);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 同键改参 409
        assertThat(registerMedia("alice", evidenceKey, commandKey, "DISK-9", sha)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerMediaWithMalformedDigestReturns400() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(registerMedia("alice", evidenceKey, uniqueKey("CMD"), "DISK-1", "not-hex")
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void createMigrationPinsSourceAndCreatesUnverifiedTarget() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");

        MvcResult result = createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey,
                "DISK-2", sourceSha);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode migration = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(migration.get("migrationKey").asText()).isEqualTo(migrationKey);
        assertThat(migration.get("sourceMediaNo").asText()).isEqualTo("DISK-1");
        assertThat(migration.get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(migration.get("targetMediaNo").asText()).isEqualTo("DISK-2");
        assertThat(migration.get("status").asText()).isEqualTo("PENDING_VERIFY");
        assertThat(migration.get("witnessId").isNull()).isTrue();
        assertThat(migration.get("version").asInt()).isEqualTo(1);

        JsonNode media = listMedia(evidenceKey);
        assertThat(media).hasSize(2);
        assertThat(media.get(1).get("mediaNo").asText()).isEqualTo("DISK-2");
        assertThat(media.get(1).get("status").asText()).isEqualTo("UNVERIFIED");
        // 迁移期间当前读取来源仍是源介质
        assertThat(objectMapper.readTree(currentMedia(evidenceKey).getResponse()
                .getContentAsString()).get("mediaNo").asText()).isEqualTo("DISK-1");
    }

    @Test
    void createMigrationByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(createMigration("mallory", evidenceKey, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", sourceSha).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createMigrationWithoutActiveMediaReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", sha256Of("x")).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createSecondPendingMigrationReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", sourceSha).getResponse().getStatus()).isEqualTo(201);
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-3", sourceSha).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createMigrationWhileBorrowedOrTransferPendingReturns409() throws Exception {
        String borrowed = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", borrowed, "DISK-1");
        assertThat(borrow("alice", borrowed).getResponse().getStatus()).isEqualTo(200);
        assertThat(createMigration("alice", borrowed, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", sourceSha).getResponse().getStatus()).isEqualTo(409);

        String pending = uniqueKey("EV");
        String pendingSha = sealedWithMedia("alice", pending, "DISK-1");
        assertThat(initiate("alice", pending, "bob").getResponse().getStatus()).isEqualTo(200);
        assertThat(createMigration("alice", pending, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", pendingSha).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void createMigrationOnSealBrokenReturns422() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", sourceSha).getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void borrowAndTransferBlockedDuringMigration() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), uniqueKey("MIG"),
                "DISK-2", sourceSha).getResponse().getStatus()).isEqualTo(201);

        assertThat(borrow("alice", evidenceKey).getResponse().getStatus()).isEqualTo(409);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus()).isEqualTo(409);
        // 证物状态未被改变，仍为 SEALED
        JsonNode chain = objectMapper.readTree(mockMvc.perform(
                        get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn().getResponse().getContentAsString());
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void verifyByCustodianReturns409EvenForReplay() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        // 保管人不能作为自己的见证者（当前权限先于重放）
        assertThat(verify("alice", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 1)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void verifyWrongDigestReturns422AndCanResubmitWithNewKey() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);

        String wrongSha = sha256Of("tampered-content");
        assertThat(verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), wrongSha, 1)
                .getResponse().getStatus()).isEqualTo(422);
        // 错摘要不改变状态：迁移仍待验证，新介质仍 UNVERIFIED
        JsonNode migration = listMigrations(evidenceKey).get(0);
        assertThat(migration.get("status").asText()).isEqualTo("PENDING_VERIFY");
        assertThat(listMedia(evidenceKey).get(1).get("status").asText()).isEqualTo("UNVERIFIED");

        // 另键提交正确摘要（须等于源摘要）验证通过
        MvcResult ok = verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 1);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode verified = objectMapper.readTree(ok.getResponse().getContentAsString());
        assertThat(verified.get("status").asText()).isEqualTo("PENDING_SWITCH");
        assertThat(verified.get("witnessId").asText()).isEqualTo("wendy");
        assertThat(verified.get("version").asInt()).isEqualTo(2);
        assertThat(listMedia(evidenceKey).get(1).get("status").asText()).isEqualTo("VERIFIED");
    }

    @Test
    void verifyWithStaleExpectedVersionReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        assertThat(verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 9)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void switchBeforeVerifyReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 1,
                "switch").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void switchByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 1);
        assertThat(decideMigration("mallory", evidenceKey, migrationKey, uniqueKey("CMD"), 2,
                "switch").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void switchMakesExactlyOneActiveAndRetiresSource() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 1);

        MvcResult switched = decideMigration("alice", evidenceKey, migrationKey,
                uniqueKey("CMD"), 2, "switch");
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        JsonNode migration = objectMapper.readTree(switched.getResponse().getContentAsString());
        assertThat(migration.get("status").asText()).isEqualTo("SWITCHED");
        assertThat(migration.get("witnessId").asText()).isEqualTo("wendy");
        assertThat(migration.get("decidedAt").isNull()).isFalse();

        // 当前只返回新介质；旧介质 RETIRED 但历史元数据保留
        JsonNode current = objectMapper.readTree(
                currentMedia(evidenceKey).getResponse().getContentAsString());
        assertThat(current.get("mediaNo").asText()).isEqualTo("DISK-2");
        assertThat(current.get("status").asText()).isEqualTo("ACTIVE");
        JsonNode media = listMedia(evidenceKey);
        assertThat(media).hasSize(2);
        assertThat(media.get(0).get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(media.get(0).get("status").asText()).isEqualTo("RETIRED");
        assertThat(media.get(0).get("retiredAt").isNull()).isFalse();
        assertThat(media.get(0).get("sha256").asText()).isEqualTo(sourceSha);

        // 切换后迁移已完结：再切换/取消/验证均 409
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 3,
                "switch").getResponse().getStatus()).isEqualTo(409);
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 3,
                "cancel").getResponse().getStatus()).isEqualTo(409);
        assertThat(verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 3)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void cancelKeepsSourceUntouchedAndRetiresTarget() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);

        MvcResult cancelled = decideMigration("alice", evidenceKey, migrationKey,
                uniqueKey("CMD"), 1, "cancel");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CANCELLED");

        // 取消不改变源副本：源介质仍 ACTIVE 且为当前读取来源；新介质 RETIRED
        JsonNode current = objectMapper.readTree(
                currentMedia(evidenceKey).getResponse().getContentAsString());
        assertThat(current.get("mediaNo").asText()).isEqualTo("DISK-1");
        JsonNode media = listMedia(evidenceKey);
        assertThat(media.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(media.get(0).get("retiredAt").isNull()).isTrue();
        assertThat(media.get(1).get("status").asText()).isEqualTo("RETIRED");

        // 已取消迁移不可再验证（新键）
        assertThat(verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 2)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void replayVerifyAfterCancelReturnsStoredResponseWithoutReviving() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        String verifyKey = uniqueKey("CMD");
        MvcResult first = verify("wendy", evidenceKey, migrationKey, verifyKey, sourceSha, 1);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        // 取消已验证迁移
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 2,
                "cancel").getResponse().getStatus()).isEqualTo(200);

        // 旧成功验证按原键重放：返回首次完整响应，但不能复活已取消迁移
        MvcResult replay = verify("wendy", evidenceKey, migrationKey, verifyKey, sourceSha, 1);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        JsonNode migration = listMigrations(evidenceKey).get(0);
        assertThat(migration.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode media = listMedia(evidenceKey);
        assertThat(media.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(media.get(1).get("status").asText()).isEqualTo("RETIRED");
    }

    @Test
    void sealFailureDuringMigrationBlocksVerifyAndSwitchButNotCancel() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 1);

        // 迁移期间封条异常仍可登记
        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        // 封条异常阻止切换
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 2,
                "switch").getResponse().getStatus()).isEqualTo(422);
        // 取消不受封条异常限制
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 2,
                "cancel").getResponse().getStatus()).isEqualTo(200);

        // 另一场景：待验证状态下封条异常阻止验证
        String ev2 = uniqueKey("EV");
        String sha2 = sealedWithMedia("alice", ev2, "DISK-1");
        String mig2 = uniqueKey("MIG");
        createMigration("alice", ev2, uniqueKey("CMD"), mig2, "DISK-2", sha2);
        inspect("alice", ev2, false);
        assertThat(verify("wendy", ev2, mig2, uniqueKey("CMD"), sha2, 1)
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void fullScenarioWrongDigestCannotSwitchThenVerifiedAndSwitched() throws Exception {
        String evidenceKey = uniqueKey("EV");
        // 合成源摘要 H
        String hashH = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", hashH);

        // 新副本错摘要 K：见证提交 K != H 返回 422，不能切换
        String hashK = sha256Of("wrong-copy");
        assertThat(verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), hashK, 1)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 1,
                "switch").getResponse().getStatus()).isEqualTo(409);

        // 另提交 H 由第二人（非保管人见证者）验证后切换
        assertThat(verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), hashH, 1)
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(decideMigration("alice", evidenceKey, migrationKey, uniqueKey("CMD"), 2,
                "switch").getResponse().getStatus()).isEqualTo(200);

        // 当前只返回新介质，旧介质仍有 RETIRED 历史
        JsonNode current = objectMapper.readTree(
                currentMedia(evidenceKey).getResponse().getContentAsString());
        assertThat(current.get("mediaNo").asText()).isEqualTo("DISK-2");
        assertThat(current.get("sha256").asText()).isEqualTo(hashH);
        JsonNode media = listMedia(evidenceKey);
        assertThat(media).hasSize(2);
        assertThat(media.get(0).get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(media.get(0).get("status").asText()).isEqualTo("RETIRED");
    }

    @Test
    void migrationEndpointsOnMissingTargetsReturn404() throws Exception {
        String missing = uniqueKey("EV");
        assertThat(registerMedia("alice", missing, uniqueKey("CMD"), "DISK-1", sha256Of("x"))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(currentMedia(missing).getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/media", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/migrations", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(verify("wendy", evidenceKey, uniqueKey("MIG"), uniqueKey("CMD"), sourceSha, 1)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(decideMigration("alice", evidenceKey, uniqueKey("MIG"), uniqueKey("CMD"), 1,
                "switch").getResponse().getStatus()).isEqualTo(404);
        // 已登记介质但无 ACTIVE 之外的当前介质时 current 仍可用；无介质证物 current 404
        String noMedia = uniqueKey("EV");
        intake("alice", noMedia);
        assertThat(currentMedia(noMedia).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void switchIdempotentReplayReturnsSameResponse() throws Exception {
        String evidenceKey = uniqueKey("EV");
        String sourceSha = sealedWithMedia("alice", evidenceKey, "DISK-1");
        String migrationKey = uniqueKey("MIG");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), migrationKey, "DISK-2", sourceSha);
        verify("wendy", evidenceKey, migrationKey, uniqueKey("CMD"), sourceSha, 1);
        String switchKey = uniqueKey("CMD");
        MvcResult first = decideMigration("alice", evidenceKey, migrationKey, switchKey, 2,
                "switch");
        MvcResult replay = decideMigration("alice", evidenceKey, migrationKey, switchKey, 2,
                "switch");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放不重复流转：仍恰好一个 ACTIVE，新介质版本保持 验证→切换 两次流转后的 3
        JsonNode media = listMedia(evidenceKey);
        assertThat(media).hasSize(2);
        assertThat(media.get(1).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(media.get(1).get("version").asInt()).isEqualTo(3);
    }
}
