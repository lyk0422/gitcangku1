package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.consent.dto.AttestRevokeRequest;
import com.example.starter.consent.dto.AttestSubmitRequest;
import com.example.starter.consent.dto.BatchBlockDetail;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RecipientDisableRequest;
import com.example.starter.consent.dto.RecipientRegisterRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 接收方证明与批次查询门禁集成测试：基于真实 H2（MODE=MySQL），
 * 覆盖精确代次作用域、批次全拒绝、撤销前后快照、接收方禁用与并发幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AttestApiTest {

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private AttestService attestService;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM batch_snapshot_item");
        jdbc.update("DELETE FROM batch_block");
        jdbc.update("DELETE FROM batch_query");
        jdbc.update("DELETE FROM recipient_attestation");
        jdbc.update("DELETE FROM attestation_scope");
        jdbc.update("DELETE FROM recipient");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.setInstant(Instant.parse("2026-09-26T00:00:00Z"));
    }

    // ---------- 辅助构造 ----------

    private void grant(String requestId, String subject, Purpose purpose) {
        consentService.grant(new GrantRequest(requestId, subject, purpose));
    }

    private void write(String requestId, String subject, Purpose purpose, String key, String payload) {
        consentService.write(new RecordWriteRequest(requestId, subject, purpose, key, payload));
    }

    private void revokeConsent(String requestId, String subject, Purpose purpose, int epoch) {
        consentService.revoke(new RevokeRequest(requestId, subject, purpose, epoch));
    }

    private void register(String requestId, String recipient, String name) {
        attestService.registerRecipient(new RecipientRegisterRequest(requestId, recipient, name));
    }

    private void disable(String requestId, String recipient) {
        attestService.disableRecipient(new RecipientDisableRequest(requestId, recipient));
    }

    private com.example.starter.consent.dto.AttestationResponse attest(
            String requestId, String recipient, Purpose purpose, int epoch,
            Instant expiresAt, String digest) {
        return attestService.submitAttestation(
                new AttestSubmitRequest(requestId, recipient, purpose, epoch, expiresAt, digest));
    }

    private Instant futureExpiry(Duration fromNow) {
        return clock.instant().plus(fromNow);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private MvcResult batchHttp(String requestId, String recipient, String purpose,
                                String subjectsJson, String recordKey) throws Exception {
        return mockMvc.perform(post("/api/v1/batch-queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","recipientId":"%s","purpose":"%s","subjectKeys":%s,"recordKey":"%s"}
                        """.formatted(requestId, recipient, purpose, subjectsJson, recordKey)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private void seedGrantedSubjectWithRecord(String subject, Purpose purpose, String recordKey, String payload) {
        grant("g-" + subject, subject, purpose);
        write("w-" + subject, subject, purpose, recordKey, payload);
    }

    // ---------- 接收方登记与禁用 ----------

    @Test
    void registerRecipientAndReplayReturnsSameResult() {
        var first = attestService.registerRecipient(new RecipientRegisterRequest("r-1", "rcp-1", "接收方甲"));
        assertThat(first.status()).isEqualTo(RecipientStatus.ENABLED);
        var replay = attestService.registerRecipient(new RecipientRegisterRequest("r-1", "rcp-1", "接收方甲"));
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM recipient")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'r-1'")).isEqualTo(1);
    }

    @Test
    void registerDuplicateRecipientReturns409() {
        register("r-1", "rcp-1", "接收方甲");
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> register("r-2", "rcp-1", "接收方乙"));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_RECIPIENT_ALREADY_EXISTS);
    }

    @Test
    void registerSameRequestIdDifferentParamsReturns409() {
        register("r-1", "rcp-1", "接收方甲");
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> register("r-1", "rcp-2", "接收方乙"));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_REQUEST_ID_CONFLICT);
    }

    @Test
    void disableUnknownRecipientReturns404AndDisableTwiceReturns409() {
        ApiException notFound = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> disable("d-1", "ghost"));
        assertThat(notFound.getStatus().value()).isEqualTo(404);
        assertThat(notFound.getCode()).isEqualTo(AttestService.CODE_RECIPIENT_NOT_FOUND);

        register("r-1", "rcp-1", "接收方甲");
        disable("d-1", "rcp-1");
        ApiException again = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> disable("d-2", "rcp-1"));
        assertThat(again.getStatus().value()).isEqualTo(409);
        assertThat(again.getCode()).isEqualTo(AttestService.CODE_RECIPIENT_ALREADY_DISABLED);
    }

    @Test
    void disableReplaySameRequestIdReturnsOriginalResult() {
        register("r-1", "rcp-1", "接收方甲");
        var first = attestService.disableRecipient(new RecipientDisableRequest("d-1", "rcp-1"));
        var replay = attestService.disableRecipient(new RecipientDisableRequest("d-1", "rcp-1"));
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'd-1'")).isEqualTo(1);
    }

    // ---------- 证明提交、到期校验与版本化续签 ----------

    @Test
    void submitAttestationCreatesVersionOne() {
        register("r-1", "rcp-1", "接收方甲");
        var attested = attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "digest-1");
        assertThat(attested.version()).isEqualTo(1);
        assertThat(attested.status()).isEqualTo(AttestationStatus.ACTIVE);
        assertThat(attested.attestationId()).isEqualTo("rcp-1#RESEARCH#1");
    }

    @Test
    void submitAttestationExpiryNotAfterNowReturns400AndDoesNotConsumeKey() {
        register("r-1", "rcp-1", "接收方甲");
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attest("a-1", "rcp-1", Purpose.RESEARCH, 1, clock.instant(), "digest-1"));
        assertThat(ex.getStatus().value()).isEqualTo(400);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_ATTESTATION_EXPIRED);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-1'")).isZero();

        // 失败不占键：同 requestId 用合规到期可成功
        var ok = attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "digest-1");
        assertThat(ok.version()).isEqualTo(1);
    }

    @Test
    void submitAttestationUnknownRecipientReturns404() {
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attest("a-1", "ghost", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d"));
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_RECIPIENT_NOT_FOUND);
    }

    @Test
    void renewalCreatesNewVersionAndKeepsHistoryWithoutOverwrite() {
        register("r-1", "rcp-1", "接收方甲");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "digest-1");
        clock.advance(Duration.ofMinutes(10));
        var renewed = attest("a-2", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(2)), "digest-2");
        assertThat(renewed.version()).isEqualTo(2);
        assertThat(renewed.status()).isEqualTo(AttestationStatus.ACTIVE);

        var history = attestService.attestationHistory("rcp-1", Purpose.RESEARCH, 1);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).version()).isEqualTo(1);
        assertThat(history.get(0).status()).isEqualTo(AttestationStatus.SUPERSEDED);
        assertThat(history.get(0).claimDigest()).isEqualTo("digest-1");
        assertThat(history.get(1).version()).isEqualTo(2);
        assertThat(history.get(1).status()).isEqualTo(AttestationStatus.ACTIVE);
        // 旧记录不被覆盖
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(2);
    }

    @Test
    void attestReplaySameRequestIdReturnsSameVersionAndDifferentParams409() {
        register("r-1", "rcp-1", "接收方甲");
        var first = attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "digest-1");
        var replay = attest("a-1", "rcp-1", Purpose.RESEARCH, 1, first.expiresAt(), "digest-1");
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(1);

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(2)), "digest-1"));
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_REQUEST_ID_CONFLICT);
    }

    @Test
    void attestationHistoryUnknownScopeReturns404() {
        register("r-1", "rcp-1", "接收方甲");
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.attestationHistory("rcp-1", Purpose.RESEARCH, 9));
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_ATTESTATION_NOT_FOUND);
    }

    // ---------- 精确代次作用域（用途迁移/拆分不得复用） ----------

    @Test
    void oldEpochAttestationCannotBeReusedAfterNewEpochAndPurposesAreIndependent() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "digest-1");

        // 用途迁移/重新授权：撤回 epoch1 后产生 epoch2
        revokeConsent("rv-1", "subj-a", Purpose.RESEARCH, 1);
        grant("g2", "subj-a", Purpose.RESEARCH);
        write("w2", "subj-a", Purpose.RESEARCH, "rec-1", "payload-a2");

        // 旧代次证明不得为新代次复用：整批 403，原因为新代次证明缺失
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1")));
        assertThat(ex.getStatus().value()).isEqualTo(403);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_BATCH_FORBIDDEN);

        // 为新代次提交证明后查询通过，快照固化为 epoch2
        attest("a-2", "rcp-1", Purpose.RESEARCH, 2, futureExpiry(Duration.ofHours(1)), "digest-2");
        var ok = attestService.batchQuery(new BatchQueryRequest("b-2", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));
        assertThat(ok.items()).hasSize(1);
        assertThat(ok.items().get(0).epoch()).isEqualTo(2);
        assertThat(ok.items().get(0).attestationVersion()).isEqualTo(1);

        // 用途相互独立：RESEARCH 的证明不能用于 PERSONALIZATION
        seedGrantedSubjectWithRecord("subj-b", Purpose.PERSONALIZATION, "rec-1", "payload-b");
        ApiException cross = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-3", "rcp-1", Purpose.PERSONALIZATION,
                        List.of("subj-b"), "rec-1")));
        assertThat(cross.getStatus().value()).isEqualTo(403);
    }

    // ---------- 批次门禁：全拒绝、稳定明细、到期 ----------

    @Test
    void batchQueryPassesOnlyWhenEverySubjectHasValidUnexpiredAttestation() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        // subj-b 经历撤回再授权，当前为 epoch2，其代次尚无证明
        grant("g-b1", "subj-b", Purpose.RESEARCH);
        revokeConsent("rv-b", "subj-b", Purpose.RESEARCH, 1);
        grant("g-b2", "subj-b", Purpose.RESEARCH);
        write("w-b2", "subj-b", Purpose.RESEARCH, "rec-1", "payload-b");
        attest("a-a", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "da");
        // 仅证明 epoch1：subj-b 的当前代次 epoch2 无证明

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a", "subj-b"), "rec-1")));
        assertThat(ex.getStatus().value()).isEqualTo(403);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> details = (java.util.Map<String, Object>) ex.getDetails();
        assertThat(details).containsKeys("batchId", "blocks");
        @SuppressWarnings("unchecked")
        List<BatchBlockDetail> blocks = (List<BatchBlockDetail>) details.get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).subjectKey()).isEqualTo("subj-b");
        assertThat(blocks.get(0).reason()).isEqualTo("ATTESTATION_MISSING");
        assertThat(blocks.get(0).epoch()).isEqualTo(2);

        // 整次拒绝：无任何部分快照
        assertThat(count("SELECT COUNT(*) FROM batch_snapshot_item")).isZero();

        // 为 epoch2 补齐证明后整批通过，两主体各自固化其当前代次
        attest("a-b", "rcp-1", Purpose.RESEARCH, 2, futureExpiry(Duration.ofHours(1)), "db");
        var ok = attestService.batchQuery(new BatchQueryRequest("b-2", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a", "subj-b"), "rec-1"));
        assertThat(ok.items()).hasSize(2);
        assertThat(ok.items()).extracting(i -> i.subjectKey()).containsExactly("subj-a", "subj-b");
        assertThat(ok.items().get(0).epoch()).isEqualTo(1);
        assertThat(ok.items().get(0).attestationVersion()).isEqualTo(1);
        assertThat(ok.items().get(0).attestationId()).isEqualTo("rcp-1#RESEARCH#1");
        assertThat(ok.items().get(1).epoch()).isEqualTo(2);
        assertThat(ok.items().get(1).attestationVersion()).isEqualTo(1);
        assertThat(ok.items().get(1).attestationId()).isEqualTo("rcp-1#RESEARCH#2");
    }

    @Test
    void subjectsAtSameEpochShareOneAttestationScope() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        seedGrantedSubjectWithRecord("subj-b", Purpose.RESEARCH, "rec-1", "payload-b");
        // 证明作用域不含主体：对 (rcp-1, RESEARCH, epoch1) 提交一次即覆盖所有当前处于 epoch1 的主体
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "shared-digest");
        var ok = attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a", "subj-b"), "rec-1"));
        assertThat(ok.items()).hasSize(2);
        assertThat(ok.items()).allSatisfy(i -> {
            assertThat(i.epoch()).isEqualTo(1);
            assertThat(i.attestationId()).isEqualTo("rcp-1#RESEARCH#1");
            assertThat(i.attestationVersion()).isEqualTo(1);
        });
    }

    @Test
    void expiredAttestationBlocksBatchWithStableSortedDetailsAndBlockAuditPersists() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        // subj-b 当前为 epoch2，对 epoch2 提交 30 分钟后到期的证明
        grant("g-b1", "subj-b", Purpose.RESEARCH);
        revokeConsent("rv-b", "subj-b", Purpose.RESEARCH, 1);
        grant("g-b2", "subj-b", Purpose.RESEARCH);
        write("w-b2", "subj-b", Purpose.RESEARCH, "rec-1", "payload-b");
        seedGrantedSubjectWithRecord("subj-c", Purpose.RESEARCH, "rec-1", "payload-c");
        attest("a-epoch1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(10)), "d-epoch1");
        attest("a-epoch2", "rcp-1", Purpose.RESEARCH, 2, futureExpiry(Duration.ofMinutes(30)), "d-epoch2");

        // 时间推进 2 小时：epoch1 证明仍有效，subj-b 所用 epoch2 证明已到期
        clock.advance(Duration.ofHours(2));

        // 阻断明细按主体稳定排序（乱序请求）
        MvcResult blocked = batchHttp("b-1", "rcp-1", "RESEARCH",
                "[\"subj-c\",\"subj-b\",\"subj-a\"]", "rec-1");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        JsonNode body = json(blocked);
        assertThat(body.get("code").asText()).isEqualTo("BATCH_QUERY_FORBIDDEN");
        String blockedBatchId = body.get("details").get("batchId").asText();
        JsonNode blocks = body.get("details").get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).get("subjectKey").asText()).isEqualTo("subj-b");
        assertThat(blocks.get(0).get("reason").asText()).isEqualTo("ATTESTATION_EXPIRED");
        assertThat(blocks.get(0).get("epoch").asInt()).isEqualTo(2);

        // 阻断批次落库可查，外层 403 回滚不影响独立事务审计；失败不占幂等键
        assertThat(count("SELECT COUNT(*) FROM batch_block WHERE batch_id = ?", blockedBatchId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-1'")).isZero();

        mockMvc.perform(get("/api/v1/batch-queries/" + blockedBatchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blocks[0].subjectKey").value("subj-b"))
                .andExpect(jsonPath("$.blocks[0].reason").value("ATTESTATION_EXPIRED"));
    }

    @Test
    void missingGrantAndMissingRecordAndRevokedGrantAreListedAsStableReasons() {
        register("r-1", "rcp-1", "接收方甲");
        // subj-a：有授权有记录有证明 → 通过
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-a", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "da");
        // subj-b：无授权
        // subj-c：有授权有证明但无该记录
        grant("g-c", "subj-c", Purpose.RESEARCH);
        attest("a-c", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "dc");
        // subj-d：授权已撤回
        grant("g-d", "subj-d", Purpose.RESEARCH);
        attest("a-d", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "dd");
        revokeConsent("rv-d", "subj-d", Purpose.RESEARCH, 1);

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-d", "subj-c", "subj-b", "subj-a"), "rec-1")));
        @SuppressWarnings("unchecked")
        List<BatchBlockDetail> blocks =
                (List<BatchBlockDetail>) ((java.util.Map<String, Object>) ex.getDetails()).get("blocks");
        assertThat(blocks).hasSize(3);
        assertThat(blocks).extracting(BatchBlockDetail::subjectKey)
                .containsExactly("subj-b", "subj-c", "subj-d");
        assertThat(blocks).extracting(BatchBlockDetail::reason)
                .containsExactly("GRANT_NOT_FOUND", "RECORD_NOT_FOUND", "GRANT_REVOKED");
        assertThat(blocks.get(2).epoch()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM batch_snapshot_item")).isZero();
    }

    @Test
    void blockedRequestDoesNotConsumeRequestIdAndCanSucceedAfterAttestationSupplied() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");

        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1")));
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-1'")).isZero();

        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");
        var ok = attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));
        assertThat(ok.items()).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-1'")).isEqualTo(1);
    }

    // ---------- 撤销只影响后续查询，快照不可改写 ----------

    @Test
    void revokingAttestationBlocksFutureQueriesButSnapshotStaysImmutable() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");

        var before = attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));
        String snapshotBatchId = before.batchId();
        assertThat(before.items().get(0).attestationVersion()).isEqualTo(1);

        // 撤销证明：后续新查询 403
        var revoked = attestService.revokeAttestation(new AttestRevokeRequest("ar-1", "rcp-1", Purpose.RESEARCH, 1));
        assertThat(revoked.status()).isEqualTo(AttestationStatus.REVOKED);
        ApiException blocked = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-2", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1")));
        assertThat(blocked.getStatus().value()).isEqualTo(403);

        // 已生成快照及其授权代次、证明版本不可改写
        var detail = attestService.getBatch(snapshotBatchId);
        assertThat(detail.status()).isEqualTo("SNAPSHOTTED");
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.items().get(0).epoch()).isEqualTo(1);
        assertThat(detail.items().get(0).attestationVersion()).isEqualTo(1);
        assertThat(detail.items().get(0).payload()).isEqualTo("payload-a");
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'REVOKED'")).isEqualTo(1);
    }

    @Test
    void snapshotRemainsImmutableAcrossPurposeMigrationAndRenewal() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "epoch1-payload");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");
        var epoch1Batch = attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));

        // 撤销证明 → 撤回授权 → 重新授权 epoch2 → 新证明 v1 → 新查询
        attestService.revokeAttestation(new AttestRevokeRequest("ar-1", "rcp-1", Purpose.RESEARCH, 1));
        revokeConsent("rv-1", "subj-a", Purpose.RESEARCH, 1);
        grant("g-2", "subj-a", Purpose.RESEARCH);
        write("w-2", "subj-a", Purpose.RESEARCH, "rec-1", "epoch2-payload");
        attest("a-2", "rcp-1", Purpose.RESEARCH, 2, futureExpiry(Duration.ofHours(1)), "d2");
        var epoch2Batch = attestService.batchQuery(new BatchQueryRequest("b-2", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));
        assertThat(epoch2Batch.items().get(0).epoch()).isEqualTo(2);
        assertThat(epoch2Batch.items().get(0).payload()).isEqualTo("epoch2-payload");

        // 旧快照内容与证明版本不变
        var oldDetail = attestService.getBatch(epoch1Batch.batchId());
        assertThat(oldDetail.items().get(0).epoch()).isEqualTo(1);
        assertThat(oldDetail.items().get(0).attestationId()).isEqualTo("rcp-1#RESEARCH#1");
        assertThat(oldDetail.items().get(0).attestationVersion()).isEqualTo(1);
        assertThat(oldDetail.items().get(0).payload()).isEqualTo("epoch1-payload");
    }

    @Test
    void revokeAttestationWithoutActiveVersionConflictsAndRevokeReplays() {
        register("r-1", "rcp-1", "接收方甲");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");
        var first = attestService.revokeAttestation(new AttestRevokeRequest("ar-1", "rcp-1", Purpose.RESEARCH, 1));
        var replay = attestService.revokeAttestation(new AttestRevokeRequest("ar-1", "rcp-1", Purpose.RESEARCH, 1));
        assertThat(replay).isEqualTo(first);

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.revokeAttestation(new AttestRevokeRequest("ar-2", "rcp-1", Purpose.RESEARCH, 1)));
        assertThat(ex.getStatus().value()).isEqualTo(409);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_ATTESTATION_ALREADY_REVOKED);
    }

    // ---------- 接收方整体禁用 ----------

    @Test
    void disabledRecipientBlocksAllNewBatchQueriesEvenWithValidAttestation() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");
        disable("d-1", "rcp-1");

        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1")));
        assertThat(ex.getStatus().value()).isEqualTo(403);
        @SuppressWarnings("unchecked")
        List<BatchBlockDetail> blocks =
                (List<BatchBlockDetail>) ((java.util.Map<String, Object>) ex.getDetails()).get("blocks");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).reason()).isEqualTo("RECIPIENT_DISABLED");
        assertThat(count("SELECT COUNT(*) FROM batch_snapshot_item")).isZero();
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentSameRequestIdAttestationsCreateExactlyOneVersion() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        attest("a-init", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d0");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return attestService.submitAttestation(new AttestSubmitRequest(
                        "a-same", "rcp-1", Purpose.RESEARCH, 1,
                        clock.instant().plus(Duration.ofHours(2)), "d-renew")).version();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'a-same'")).isEqualTo(1);
    }

    @Test
    void concurrentRenewalsWithDistinctRequestsCreateContiguousVersions() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        attest("a-init", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d0");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return attestService.submitAttestation(new AttestSubmitRequest(
                        "a-distinct-" + index, "rcp-1", Purpose.RESEARCH, 1,
                        clock.instant().plus(Duration.ofHours(2 + index)), "d-" + index)).version();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Integer> versions = new ArrayList<>();
        for (Future<Integer> future : futures) {
            versions.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(versions).containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(versions.stream().max(Integer::compareTo).orElseThrow()).isEqualTo(
                attestService.attestationHistory("rcp-1", Purpose.RESEARCH, 1).size());
    }

    @Test
    void concurrentSameRequestIdBatchQueriesCreateSingleSnapshotBatch() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        seedGrantedSubjectWithRecord("subj-b", Purpose.RESEARCH, "rec-1", "payload-b");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return attestService.batchQuery(new BatchQueryRequest("b-same", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a", "subj-b"), "rec-1")).batchId();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String batchId = null;
        for (Future<String> future : futures) {
            String id = future.get(30, TimeUnit.SECONDS);
            if (batchId == null) {
                batchId = id;
            }
            assertThat(id).isEqualTo(batchId);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM batch_query WHERE status = 'SNAPSHOTTED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM batch_snapshot_item")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'b-same'")).isEqualTo(1);
    }

    @Test
    void concurrentDisableAndBatchQueryIsAdjudicatedByCommitOrderWithConsistentOutcome() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> queryTask = () -> {
            ready.countDown();
            start.await();
            try {
                attestService.batchQuery(new BatchQueryRequest("b-race", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1"));
                return "SNAPSHOTTED";
            } catch (ApiException e) {
                return "BLOCKED";
            }
        };
        Callable<String> disableTask = () -> {
            ready.countDown();
            start.await();
            attestService.disableRecipient(new RecipientDisableRequest("d-race", "rcp-1"));
            return "DISABLED";
        };

        Future<String> queryResult = pool.submit(queryTask);
        Future<String> disableResult = pool.submit(disableTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String outcome = queryResult.get(30, TimeUnit.SECONDS);
        assertThat(disableResult.get(30, TimeUnit.SECONDS)).isEqualTo("DISABLED");
        pool.shutdown();

        assertThat(outcome).isIn("SNAPSHOTTED", "BLOCKED");
        // 终态一致：接收方已禁用；查询要么留下一个快照批次，要么留下一个阻断批次，二者恰好其一
        assertThat(count("SELECT COUNT(*) FROM recipient WHERE status = 'DISABLED'")).isEqualTo(1);
        int snapshots = count("SELECT COUNT(*) FROM batch_query WHERE status = 'SNAPSHOTTED'");
        int blocks = count("SELECT COUNT(*) FROM batch_query WHERE status = 'BLOCKED'");
        assertThat(snapshots + blocks).isEqualTo(1);
        if (outcome.equals("SNAPSHOTTED")) {
            assertThat(snapshots).isEqualTo(1);
            // 禁用后的新查询必须 403
            ApiException after = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                    () -> attestService.batchQuery(new BatchQueryRequest("b-after", "rcp-1", Purpose.RESEARCH,
                            List.of("subj-a"), "rec-1")));
            assertThat(after.getStatus().value()).isEqualTo(403);
        } else {
            assertThat(blocks).isEqualTo(1);
        }
    }

    @Test
    void concurrentRenewalAndBatchQueryIsAdjudicatedByCommitOrder() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> renewTask = () -> {
            ready.countDown();
            start.await();
            return attestService.submitAttestation(new AttestSubmitRequest(
                    "a-renew", "rcp-1", Purpose.RESEARCH, 1,
                    clock.instant().plus(Duration.ofHours(2)), "d2")).version();
        };
        Callable<Integer> queryTask = () -> {
            ready.countDown();
            start.await();
            return attestService.batchQuery(new BatchQueryRequest("b-race", "rcp-1", Purpose.RESEARCH,
                    List.of("subj-a"), "rec-1")).items().get(0).attestationVersion();
        };
        Future<Integer> renewResult = pool.submit(renewTask);
        Future<Integer> queryResult = pool.submit(queryTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(renewResult.get(30, TimeUnit.SECONDS)).isEqualTo(2);
        int snapshotVersion = queryResult.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 查询与续签按提交顺序裁决：快照要么固化旧版本 1，要么固化新版本 2，且二者均合法
        assertThat(snapshotVersion).isIn(1, 2);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM batch_snapshot_item")).isEqualTo(1);
    }

    @Test
    void concurrentAttestationRevokeAndBatchQueryIsAdjudicatedByCommitOrder() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> revokeTask = () -> {
            ready.countDown();
            start.await();
            attestService.revokeAttestation(new AttestRevokeRequest("ar-race", "rcp-1", Purpose.RESEARCH, 1));
            return "REVOKED";
        };
        Callable<String> queryTask = () -> {
            ready.countDown();
            start.await();
            try {
                attestService.batchQuery(new BatchQueryRequest("b-race", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1"));
                return "SNAPSHOTTED";
            } catch (ApiException e) {
                assertThat(e.getStatus().value()).isEqualTo(403);
                return "BLOCKED";
            }
        };
        Future<String> revokeResult = pool.submit(revokeTask);
        Future<String> queryResult = pool.submit(queryTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(revokeResult.get(30, TimeUnit.SECONDS)).isEqualTo("REVOKED");
        String outcome = queryResult.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 撤销与查询按提交顺序裁决：要么快照成功（撤销未生效于本批），要么整批 403
        assertThat(outcome).isIn("SNAPSHOTTED", "BLOCKED");
        assertThat(count("SELECT COUNT(*) FROM recipient_attestation WHERE status = 'REVOKED'")).isEqualTo(1);
        int snapshots = count("SELECT COUNT(*) FROM batch_query WHERE status = 'SNAPSHOTTED'");
        int blocks = count("SELECT COUNT(*) FROM batch_query WHERE status = 'BLOCKED'");
        assertThat(snapshots + blocks).isEqualTo(1);
        // 撤销后新的查询必须 403
        ApiException after = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-after", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1")));
        assertThat(after.getStatus().value()).isEqualTo(403);
    }

    @Test
    void concurrentPurposeMigrationAndBatchQueryIsAdjudicatedByCommitOrder() throws Exception {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        // 用途迁移：撤回 epoch1 并重新授权产生 epoch2（单事务内提交）
        Callable<String> migrateTask = () -> {
            ready.countDown();
            start.await();
            consentService.revoke(new RevokeRequest("rv-race", "subj-a", Purpose.RESEARCH, 1));
            consentService.grant(new GrantRequest("g-race", "subj-a", Purpose.RESEARCH));
            return "MIGRATED";
        };
        Callable<String> queryTask = () -> {
            ready.countDown();
            start.await();
            try {
                var response = attestService.batchQuery(new BatchQueryRequest("b-race", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1"));
                return "SNAPSHOTTED_EPOCH_" + response.items().get(0).epoch();
            } catch (ApiException e) {
                assertThat(e.getStatus().value()).isEqualTo(403);
                return "BLOCKED";
            }
        };
        Future<String> migrateResult = pool.submit(migrateTask);
        Future<String> queryResult = pool.submit(queryTask);
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(migrateResult.get(30, TimeUnit.SECONDS)).isEqualTo("MIGRATED");
        String outcome = queryResult.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 迁移与查询按提交顺序裁决：要么快照固化旧代次 epoch1，要么整批 403（新代次无证明）
        assertThat(outcome).isIn("SNAPSHOTTED_EPOCH_1", "BLOCKED");
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 2 AND status = 'ACTIVE'")).isEqualTo(1);
        // 迁移后新代次查询：旧代次证明不得复用，必须 403
        ApiException after = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.batchQuery(new BatchQueryRequest("b-after", "rcp-1", Purpose.RESEARCH,
                        List.of("subj-a"), "rec-1")));
        assertThat(after.getStatus().value()).isEqualTo(403);
    }

    @Test
    void batchQueryReplaySameRequestIdReturnsOriginalSnapshot() {
        register("r-1", "rcp-1", "接收方甲");
        seedGrantedSubjectWithRecord("subj-a", Purpose.RESEARCH, "rec-1", "payload-a");
        attest("a-1", "rcp-1", Purpose.RESEARCH, 1, futureExpiry(Duration.ofHours(1)), "d1");
        var first = attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));
        var replay = attestService.batchQuery(new BatchQueryRequest("b-1", "rcp-1", Purpose.RESEARCH,
                List.of("subj-a"), "rec-1"));
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM batch_query WHERE status = 'SNAPSHOTTED'")).isEqualTo(1);
    }

    @Test
    void getUnknownBatchReturns404() {
        ApiException ex = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> attestService.getBatch("nonexistent"));
        assertThat(ex.getStatus().value()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo(AttestService.CODE_BATCH_NOT_FOUND);
    }
}
