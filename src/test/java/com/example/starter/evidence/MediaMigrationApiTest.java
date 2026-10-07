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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 介质迁移 API 主流程与失败分支测试：登记首个 ACTIVE 介质、创建迁移、见证验证、
 * 显式切换、取消，以及迁移期间对借出/交接的阻塞与封条异常的阻断。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MediaMigrationApiTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    /** 合成源摘要 H。 */
    private static final String HASH_H = "a".repeat(64);
    /** 新副本错摘要 K。 */
    private static final String HASH_K = "b".repeat(64);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private MvcResult intake(String actor, String evidenceKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("evidenceKey", evidenceKey);
        body.put("caseKey", "CASE-1");
        body.put("category", "DIGITAL");
        body.put("sealNo", "SEAL-1");
        return mockMvc.perform(post("/api/evidence")
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult registerMedia(String actor, String evidenceKey, String commandKey,
                                    String mediaNo, String sha256) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("mediaNo", mediaNo);
        body.put("sha256", sha256);
        return mockMvc.perform(post("/api/evidence/{key}/media", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult createMigration(String actor, String evidenceKey, String commandKey,
                                      String targetMediaNo, String targetSha256) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("targetMediaNo", targetMediaNo);
        body.put("targetSha256", targetSha256);
        return mockMvc.perform(post("/api/evidence/{key}/media-migrations", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult verify(String actor, String evidenceKey, String commandKey,
                             String sha256) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        body.put("sha256", sha256);
        return mockMvc.perform(post("/api/evidence/{key}/media-migrations/verify", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult decideMigration(String actor, String evidenceKey, String commandKey,
                                      String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", commandKey);
        return mockMvc.perform(
                post("/api/evidence/{key}/media-migrations/{action}", evidenceKey, action)
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

    private MvcResult initiate(String actor, String evidenceKey, String toCustodian)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("toCustodian", toCustodian);
        return mockMvc.perform(post("/api/evidence/{key}/transfers", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult borrow(String actor, String evidenceKey, String borrower) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("commandKey", uniqueKey("CMD"));
        body.put("loanKey", uniqueKey("LOAN"));
        body.put("borrowerId", borrower);
        body.put("purpose", "鉴定用");
        body.put("dueAt", LocalDateTime.now().plusHours(1).toString());
        return mockMvc.perform(post("/api/evidence/{key}/loans", evidenceKey)
                        .header(ACTOR_HEADER, actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode currentMedia(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/media/current", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listMedia(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/evidence/{key}/media", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode listMigrations(String evidenceKey) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/evidence/{key}/media-migrations", evidenceKey))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String intakeWithMedia(String actor, String evidenceKey, String mediaNo)
            throws Exception {
        assertThat(intake(actor, evidenceKey).getResponse().getStatus()).isEqualTo(201);
        assertThat(registerMedia(actor, evidenceKey, uniqueKey("CMD"), mediaNo, HASH_H)
                .getResponse().getStatus()).isEqualTo(201);
        return mediaNo;
    }

    @Test
    void registerFirstMediaBecomesActiveCurrentSource() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);

        MvcResult result = registerMedia("alice", evidenceKey, uniqueKey("CMD"), "DISK-1", HASH_H);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode media = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(media.get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(media.get("sha256").asText()).isEqualTo(HASH_H);
        assertThat(media.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode current = currentMedia(evidenceKey);
        assertThat(current.get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(current.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void registerMediaByNonCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(registerMedia("mallory", evidenceKey, uniqueKey("CMD"), "DISK-1", HASH_H)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerSecondMediaDirectlyReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(registerMedia("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void mediaEndpointsOnMissingEvidenceReturn404() throws Exception {
        String missing = uniqueKey("EV");
        assertThat(registerMedia("alice", missing, uniqueKey("CMD"), "DISK-1", HASH_H)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(createMigration("alice", missing, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(verify("bob", missing, uniqueKey("CMD"), HASH_H)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(decideMigration("alice", missing, uniqueKey("CMD"), "switch")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/media", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/media/current", missing))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void currentMediaReturns404WhenNoMediaRegistered() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        assertThat(mockMvc.perform(get("/api/evidence/{key}/media/current", evidenceKey))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void fullMigrationFlowWrongDigestBlockedThenVerifiedAndSwitched() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");

        // 创建迁移：固定源介质 DISK-1 与新介质 DISK-2，新介质仅为 UNVERIFIED
        MvcResult created = createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode migration = objectMapper.readTree(created.getResponse().getContentAsString());
        assertThat(migration.get("sourceMediaNo").asText()).isEqualTo("DISK-1");
        assertThat(migration.get("targetMediaNo").asText()).isEqualTo("DISK-2");
        assertThat(migration.get("status").asText()).isEqualTo("PENDING");
        JsonNode target = listMedia(evidenceKey).get(1);
        assertThat(target.get("status").asText()).isEqualTo("UNVERIFIED");

        // 新副本错摘要 K：验证 422，不能切换
        assertThat(verify("bob", evidenceKey, uniqueKey("CMD"), HASH_K)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(decideMigration("alice", evidenceKey, uniqueKey("CMD"), "switch")
                .getResponse().getStatus()).isEqualTo(409);

        // 另提交 H 由第二人（非保管人见证者）验证通过
        MvcResult verified = verify("bob", evidenceKey, uniqueKey("CMD"), HASH_H);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        JsonNode verifiedMigration = objectMapper.readTree(
                verified.getResponse().getContentAsString());
        assertThat(verifiedMigration.get("witnessId").asText()).isEqualTo("bob");
        assertThat(listMedia(evidenceKey).get(1).get("status").asText()).isEqualTo("VERIFIED");

        // 保管人显式切换：新介质 ACTIVE、旧介质 RETIRED，冻结摘要与见证人
        MvcResult switched = decideMigration("alice", evidenceKey, uniqueKey("CMD"), "switch");
        assertThat(switched.getResponse().getStatus()).isEqualTo(200);
        JsonNode completed = objectMapper.readTree(switched.getResponse().getContentAsString());
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("sourceSha256").asText()).isEqualTo(HASH_H);
        assertThat(completed.get("targetSha256").asText()).isEqualTo(HASH_H);
        assertThat(completed.get("witnessId").asText()).isEqualTo("bob");
        assertThat(completed.get("decidedAt").isNull()).isFalse();

        // 当前只返回新介质；旧介质保留 RETIRED 历史；恰好一个 ACTIVE
        JsonNode current = currentMedia(evidenceKey);
        assertThat(current.get("mediaNo").asText()).isEqualTo("DISK-2");
        assertThat(current.get("status").asText()).isEqualTo("ACTIVE");
        JsonNode all = listMedia(evidenceKey);
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(all.get(0).get("status").asText()).isEqualTo("RETIRED");
        assertThat(all.get(1).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void verifyByCustodianReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);
        assertThat(verify("alice", evidenceKey, uniqueKey("CMD"), HASH_H)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void failedVerifyDoesNotConsumeCommandKeyAndAllowsResubmit() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);

        String commandKey = uniqueKey("CMD");
        // 错摘要 422，失败不写幂等日志，同键可复用
        assertThat(verify("bob", evidenceKey, commandKey, HASH_K)
                .getResponse().getStatus()).isEqualTo(422);
        MvcResult retried = verify("bob", evidenceKey, commandKey, HASH_H);
        assertThat(retried.getResponse().getStatus()).isEqualTo(200);
        // 同键同参重放返回首次完整响应
        MvcResult replay = verify("bob", evidenceKey, commandKey, HASH_H);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(retried.getResponse().getContentAsString());
    }

    @Test
    void verifyWithoutPendingMigrationReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(verify("bob", evidenceKey, uniqueKey("CMD"), HASH_H)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void secondPendingMigrationReturns409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(201);
        assertThat(createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-3", HASH_K)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(listMigrations(evidenceKey)).hasSize(1);
    }

    @Test
    void createMigrationRequiresNormalSealedEvidence() throws Exception {
        // 待交接期间 409
        String evPending = uniqueKey("EV");
        intakeWithMedia("alice", evPending, "DISK-1");
        initiate("alice", evPending, "bob");
        assertThat(createMigration("alice", evPending, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(409);

        // 借出期间 409
        String evBorrowed = uniqueKey("EV");
        intakeWithMedia("alice", evBorrowed, "DISK-1");
        assertThat(borrow("alice", evBorrowed, "carol").getResponse().getStatus()).isEqualTo(200);
        assertThat(createMigration("alice", evBorrowed, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(409);

        // 封条异常 422
        String evBroken = uniqueKey("EV");
        intakeWithMedia("alice", evBroken, "DISK-1");
        inspect("alice", evBroken, false);
        assertThat(createMigration("alice", evBroken, uniqueKey("CMD"), "DISK-2", HASH_K)
                .getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void pendingMigrationBlocksLoanAndTransfer() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);

        assertThat(borrow("alice", evidenceKey, "carol").getResponse().getStatus())
                .isEqualTo(409);
        assertThat(initiate("alice", evidenceKey, "bob").getResponse().getStatus())
                .isEqualTo(409);
        // 保管人未被修改
        JsonNode chain = objectMapper.readTree(mockMvc
                .perform(get("/api/evidence/{key}/custody-chain", evidenceKey))
                .andReturn().getResponse().getContentAsString());
        assertThat(chain.get("evidence").get("custodianId").asText()).isEqualTo("alice");
        assertThat(chain.get("evidence").get("status").asText()).isEqualTo("SEALED");
    }

    @Test
    void sealInspectionRecordableDuringMigrationAndBlocksVerifyAndSwitch() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);

        // 迁移期间封条异常仍可登记
        assertThat(inspect("alice", evidenceKey, false).getResponse().getStatus()).isEqualTo(200);
        // 封条异常阻止验证与切换
        assertThat(verify("bob", evidenceKey, uniqueKey("CMD"), HASH_H)
                .getResponse().getStatus()).isEqualTo(422);
        assertThat(decideMigration("alice", evidenceKey, uniqueKey("CMD"), "switch")
                .getResponse().getStatus()).isEqualTo(422);
        // 源副本仍为当前介质
        assertThat(currentMedia(evidenceKey).get("mediaNo").asText()).isEqualTo("DISK-1");
    }

    @Test
    void cancelKeepsSourceActiveAndBlocksFurtherDecisions() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);

        MvcResult cancelled = decideMigration("alice", evidenceKey, uniqueKey("CMD"), "cancel");
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(cancelled.getResponse().getContentAsString())
                .get("status").asText()).isEqualTo("CANCELLED");

        // 取消不改变源副本
        JsonNode current = currentMedia(evidenceKey);
        assertThat(current.get("mediaNo").asText()).isEqualTo("DISK-1");
        assertThat(current.get("status").asText()).isEqualTo("ACTIVE");

        // 已取消迁移不能再验证或切换
        assertThat(verify("bob", evidenceKey, uniqueKey("CMD"), HASH_H)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideMigration("alice", evidenceKey, uniqueKey("CMD"), "switch")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideMigration("alice", evidenceKey, uniqueKey("CMD"), "cancel")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void oldVerifyReplayDoesNotReviveCancelledMigration() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);

        String verifyKey = uniqueKey("CMD");
        MvcResult first = verify("bob", evidenceKey, verifyKey, HASH_H);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        decideMigration("alice", evidenceKey, uniqueKey("CMD"), "cancel");

        // 旧成功验证重放：返回首次响应但不改变已取消的迁移
        MvcResult replay = verify("bob", evidenceKey, verifyKey, HASH_H);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        JsonNode migrations = listMigrations(evidenceKey);
        assertThat(migrations).hasSize(1);
        assertThat(migrations.get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(currentMedia(evidenceKey).get("mediaNo").asText()).isEqualTo("DISK-1");
    }

    @Test
    void switchAndCancelByNonCustodianReturn409() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);
        verify("bob", evidenceKey, uniqueKey("CMD"), HASH_H);

        assertThat(decideMigration("mallory", evidenceKey, uniqueKey("CMD"), "switch")
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(decideMigration("mallory", evidenceKey, uniqueKey("CMD"), "cancel")
                .getResponse().getStatus()).isEqualTo(409);
        // 迁移仍待完成，保管人可继续切换
        assertThat(decideMigration("alice", evidenceKey, uniqueKey("CMD"), "switch")
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void createMigrationIdempotentReplayAndSameKeyDifferentParams() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");

        String commandKey = uniqueKey("CMD");
        MvcResult first = createMigration("alice", evidenceKey, commandKey, "DISK-2", HASH_K);
        MvcResult replay = createMigration("alice", evidenceKey, commandKey, "DISK-2", HASH_K);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(listMigrations(evidenceKey)).hasSize(1);
        assertThat(listMedia(evidenceKey)).hasSize(2);

        // 同键改参 409
        assertThat(createMigration("alice", evidenceKey, commandKey, "DISK-3", HASH_K)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void registerMediaIdempotentReplayReturnsFirstResult() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intake("alice", evidenceKey);
        String commandKey = uniqueKey("CMD");
        MvcResult first = registerMedia("alice", evidenceKey, commandKey, "DISK-1", HASH_H);
        MvcResult replay = registerMedia("alice", evidenceKey, commandKey, "DISK-1", HASH_H);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(listMedia(evidenceKey)).hasSize(1);
        // 同键改参 409
        assertThat(registerMedia("alice", evidenceKey, commandKey, "DISK-9", HASH_H)
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void switchIdempotentReplayDoesNotRetireTwice() throws Exception {
        String evidenceKey = uniqueKey("EV");
        intakeWithMedia("alice", evidenceKey, "DISK-1");
        createMigration("alice", evidenceKey, uniqueKey("CMD"), "DISK-2", HASH_K);
        verify("bob", evidenceKey, uniqueKey("CMD"), HASH_H);

        String switchKey = uniqueKey("CMD");
        MvcResult first = decideMigration("alice", evidenceKey, switchKey, "switch");
        MvcResult replay = decideMigration("alice", evidenceKey, switchKey, "switch");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        // 重放后仍恰好一个 ACTIVE，迁移记录只有一条
        JsonNode all = listMedia(evidenceKey);
        assertThat(all).hasSize(2);
        assertThat(all.findValuesAsText("status")).containsExactlyInAnyOrder("RETIRED", "ACTIVE");
        assertThat(listMigrations(evidenceKey)).hasSize(1);
    }
}
