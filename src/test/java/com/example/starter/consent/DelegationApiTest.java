package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.consent.ClockTestConfig.MutableTimeSource;
import com.example.starter.consent.dto.ChainResponse;
import com.example.starter.consent.dto.DelegateRequest;
import com.example.starter.consent.dto.DelegationResponse;
import com.example.starter.consent.dto.DelegationRevokeRequest;
import com.example.starter.consent.dto.EdgeVersionInput;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import org.springframework.context.annotation.Import;

/**
 * 限时授权与委托链的真实 H2 数据库测试：覆盖主流程、失败分支、到期/撤销级联、
 * 事务整体回滚、幂等边界与提交顺序并发。
 */
@SpringBootTest
@Import(ClockTestConfig.class)
class DelegationApiTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final String SUBJ = "subj-a";
    private static final Instant GRANT_EXPIRY = T0.plus(Duration.ofDays(365));

    @Autowired
    private ConsentService consentService;
    @Autowired
    private DelegationService delegationService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableTimeSource clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_delegation");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
        clock.set(T0);
    }

    // ---------- 辅助方法 ----------

    private com.example.starter.consent.dto.GrantResponse grant(String reqId, Instant expiry) {
        return consentService.grant(new GrantRequest(reqId, SUBJ, Purpose.RESEARCH, expiry));
    }

    private int grantEpoch(String reqId) {
        return consentService.grant(new GrantRequest(reqId, SUBJ, Purpose.RESEARCH, GRANT_EXPIRY)).epoch();
    }

    private DelegationResponse delegate(String reqId, String key, String from, String to, Instant expiry) {
        return delegationService.delegate(
                new DelegateRequest(reqId, key, SUBJ, Purpose.RESEARCH, from, to, expiry));
    }

    private DelegationResponse delegateFuture(String reqId, String key, String from, String to) {
        return delegate(reqId, key, from, to, GRANT_EXPIRY.minus(Duration.ofDays(1)));
    }

    private EdgeVersionInput ref(String key, int version) {
        return new EdgeVersionInput(key, version);
    }

    private RecordResponse write(String reqId, String caller, String recordKey, String payload,
                                 List<EdgeVersionInput> path) {
        return consentService.write(new RecordWriteRequest(
                reqId, SUBJ, Purpose.RESEARCH, caller, recordKey, payload, path));
    }

    private void expectApi(ThrowingCall call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    // ---------- 主流程 ----------

    @Test
    void processorWritesAlongTwoHopChainAndBasisIsImmutable() {
        grantEpoch("g1");
        delegateFuture("d-req-1", "e1", SUBJ, "p1");
        delegateFuture("d-req-2", "e2", "p1", "p2");

        RecordResponse saved = write("w1", "p2", "rec-1", "data-1",
                List.of(ref("e1", 1), ref("e2", 1)));
        assertThat(saved.epoch()).isEqualTo(1);
        assertThat(saved.callerKey()).isEqualTo("p2");
        assertThat(saved.evaluatedAt()).isEqualTo(T0);
        assertThat(saved.delegationPath()).hasSize(2);
        assertThat(saved.delegationPath().get(0).delegationKey()).isEqualTo("e1");
        assertThat(saved.delegationPath().get(1).toKey()).isEqualTo("p2");

        // 当前有效链查询：主体->p1->p2 的有序最短路径
        ChainResponse chain = delegationService.currentChain(SUBJ, Purpose.RESEARCH, null, "p2");
        assertThat(chain.edges()).hasSize(2);
        assertThat(chain.edges().get(0).fromKey()).isEqualTo(SUBJ);
        assertThat(chain.edges().get(1).toKey()).isEqualTo("p2");

        // 主体直写仍可用，且不产生委托快照
        RecordResponse direct = write("w0", SUBJ, "rec-0", "direct", List.of());
        assertThat(direct.delegationPath()).isEmpty();

        // 记录查询返回不可变写入依据
        RecordResponse loaded = consentService.read(SUBJ, Purpose.RESEARCH, "rec-1");
        assertThat(loaded.payload()).isEqualTo("data-1");
        assertThat(loaded.delegationPath()).usingRecursiveComparison().isEqualTo(saved.delegationPath());
    }

    @Test
    void currentChainForSubjectItselfIsEmpty() {
        grantEpoch("g1");
        ChainResponse chain = delegationService.currentChain(SUBJ, Purpose.RESEARCH, null, SUBJ);
        assertThat(chain.edges()).isEmpty();
        assertThat(chain.epoch()).isEqualTo(1);
    }

    @Test
    void chainAcrossFiveHopsSucceedsAndSixthIsRejected() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        delegateFuture("r2", "e2", "p1", "p2");
        delegateFuture("r3", "e3", "p2", "p3");
        delegateFuture("r4", "e4", "p3", "p4");
        delegateFuture("r5", "e5", "p4", "p5");

        List<EdgeVersionInput> path = List.of(ref("e1", 1), ref("e2", 1), ref("e3", 1),
                ref("e4", 1), ref("e5", 1));
        assertThat(write("w5", "p5", "rec-5", "deep", path).delegationPath()).hasSize(5);

        expectApi(() -> delegateFuture("r6", "e6", "p5", "p6"),
                HttpStatus.FORBIDDEN, "DELEGATION_DEPTH_EXCEEDED");
        assertThat(count("SELECT COUNT(*) FROM consent_delegation WHERE delegation_key = 'e6'")).isZero();
    }

    // ---------- 失败分支 ----------

    @Test
    void writeWithMissingEdgeIsRejected() {
        grantEpoch("g1");
        expectApi(() -> write("w1", "p1", "rec-1", "x", List.of(ref("missing", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_MISSING");
    }

    @Test
    void writeWithWrongVersionIs409() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        expectApi(() -> write("w1", "p1", "rec-1", "x", List.of(ref("e1", 9))),
                HttpStatus.CONFLICT, "EDGE_VERSION_CONFLICT");
    }

    @Test
    void writePathNotEndingAtCallerIsRejected() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        expectApi(() -> write("w1", "p2", "rec-1", "x", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_PATH_INVALID");
    }

    @Test
    void subjectWriteMustNotCarryPathAndProcessorWriteMustCarryPath() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        expectApi(() -> write("w1", SUBJ, "rec-1", "x", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_PATH_INVALID");
        expectApi(() -> write("w2", "p1", "rec-2", "x", List.of()),
                HttpStatus.FORBIDDEN, "DELEGATION_PATH_INVALID");
    }

    @Test
    void nonShortestPathIsRejectedAfterShorterEdgeCreated() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        delegateFuture("r2", "e2", "p1", "p2");
        // 新增直达边 subj->p2，原两跳路径不再最短
        delegateFuture("r3", "e3", SUBJ, "p2");

        expectApi(() -> write("w1", "p2", "rec-1", "x", List.of(ref("e1", 1), ref("e2", 1))),
                HttpStatus.CONFLICT, "DELEGATION_PATH_NOT_SHORTEST");
        // 最短直达路径可写
        assertThat(write("w2", "p2", "rec-2", "ok", List.of(ref("e3", 1))).delegationPath())
                .hasSize(1);
    }

    @Test
    void cycleAndDuplicateAndUnauthorizedDelegationAreRejected() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        delegateFuture("r2", "e2", "p1", "p2");

        // 指回主体成环
        expectApi(() -> delegateFuture("r3", "e3", "p2", SUBJ),
                HttpStatus.FORBIDDEN, "DELEGATION_CYCLE");
        // 反向边成环
        expectApi(() -> delegateFuture("r4", "e4", "p2", "p1"),
                HttpStatus.FORBIDDEN, "DELEGATION_CYCLE");
        // 重复有效边
        expectApi(() -> delegateFuture("r5", "e1dup", SUBJ, "p1"),
                HttpStatus.CONFLICT, "DELEGATION_EDGE_EXISTS");
        // 未获委托的处理方不能继续向下委托
        expectApi(() -> delegateFuture("r6", "e6", "pX", "pY"),
                HttpStatus.FORBIDDEN, "DELEGATION_NOT_AUTHORIZED");
    }

    @Test
    void delegationKeyIsGloballyUnique() {
        grantEpoch("g1");
        delegateFuture("r1", "shared-key", SUBJ, "p1");
        expectApi(() -> delegateFuture("r2", "shared-key", SUBJ, "p2"),
                HttpStatus.CONFLICT, "DELEGATION_KEY_CONFLICT");
    }

    @Test
    void delegationExpiryMustNotExceedParent() {
        grantEpoch("g1");
        delegate("r1", "e1", SUBJ, "p1", T0.plus(Duration.ofDays(10)));
        // 子边到期晚于父边
        expectApi(() -> delegate("r2", "e2", "p1", "p2", T0.plus(Duration.ofDays(20))),
                HttpStatus.FORBIDDEN, "DELEGATION_EXPIRES_AFTER_PARENT");
        // 到期时刻不晚于当前时刻
        expectApi(() -> delegate("r3", "e3", SUBJ, "p3", T0),
                HttpStatus.BAD_REQUEST, "GRANT_EXPIRES_INVALID");
    }

    @Test
    void expiredEdgeBlocksWritesAndChainQuery() {
        grantEpoch("g1");
        Instant edgeExpiry = T0.plus(Duration.ofDays(10));
        delegate("r1", "e1", SUBJ, "p1", edgeExpiry);
        assertThat(write("w0", "p1", "rec-0", "ok", List.of(ref("e1", 1))).payload()).isEqualTo("ok");

        // 恰在到期时刻已无效（有效期要求 expiresAt > now）
        clock.set(edgeExpiry);
        expectApi(() -> write("w1", "p1", "rec-1", "x", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_EXPIRED");
        expectApi(() -> delegationService.currentChain(SUBJ, Purpose.RESEARCH, null, "p1"),
                HttpStatus.NOT_FOUND, "DELEGATION_CHAIN_NOT_FOUND");

        // 旧边到期后续建不晚于上级的新边：版本递增到 2，凭新版本恢复写入
        clock.set(edgeExpiry.plusSeconds(1));
        Instant renewedExpiry = GRANT_EXPIRY.minus(Duration.ofDays(2));
        DelegationResponse renewed = delegate("r2", "e1b", SUBJ, "p1", renewedExpiry);
        assertThat(renewed.version()).isEqualTo(2);
        assertThat(write("w2", "p1", "rec-2", "renewed", List.of(ref("e1b", 2))).payload())
                .isEqualTo("renewed");
        // 持旧版本号 1 引用新边属于版本不符
        expectApi(() -> write("w3", "p1", "rec-3", "x", List.of(ref("e1b", 1))),
                HttpStatus.CONFLICT, "EDGE_VERSION_CONFLICT");

        // 新边到期后再次失效
        clock.set(renewedExpiry);
        expectApi(() -> write("w4", "p1", "rec-4", "x", List.of(ref("e1b", 2))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_EXPIRED");
    }

    @Test
    void expiredGrantBlocksWriteDelegateAndChainAndAllowsNextEpoch() {
        grant("g1", T0.plus(Duration.ofDays(10)));
        delegate("d1", "e1", SUBJ, "p1", T0.plus(Duration.ofDays(10)));
        write("w1", "p1", "rec-old", "old", List.of(ref("e1", 1)));

        clock.set(T0.plus(Duration.ofDays(11)));
        expectApi(() -> write("w2", "p1", "rec-2", "x", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "CONSENT_EXPIRED");
        expectApi(() -> delegateFuture("d2", "e2", SUBJ, "p2"),
                HttpStatus.FORBIDDEN, "CONSENT_EXPIRED");
        expectApi(() -> delegationService.currentChain(SUBJ, Purpose.RESEARCH, null, "p1"),
                HttpStatus.FORBIDDEN, "CONSENT_EXPIRED");

        // 到期后重新授权生成下一代，新代读不到旧代数据
        GrantRequest regrant = new GrantRequest("g2", SUBJ, Purpose.RESEARCH, T0.plus(Duration.ofDays(400)));
        assertThat(consentService.grant(regrant).epoch()).isEqualTo(2);
        expectApi(() -> consentService.read(SUBJ, Purpose.RESEARCH, "rec-old"),
                HttpStatus.NOT_FOUND, "RECORD_NOT_FOUND");
        // 旧数据物理保留
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE epoch = 1")).isEqualTo(1);
        // 旧代委托边不可用于新代写入
        expectApi(() -> write("w3", "p1", "rec-3", "x", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_MISSING");
    }

    @Test
    void revokingEdgeOnlyAffectsLaterWritesButBasisStaysReadable() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        delegateFuture("r2", "e2", "p1", "p2");
        write("w1", "p2", "rec-1", "before-revoke", List.of(ref("e1", 1), ref("e2", 1)));

        delegationService.revoke(new DelegationRevokeRequest("rv1", "e1"));

        // 撤销后处理方新写入被拒，主体写入不受影响
        expectApi(() -> write("w2", "p2", "rec-2", "x", List.of(ref("e1", 1), ref("e2", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_MISSING");
        assertThat(write("w0", SUBJ, "rec-0", "direct", List.of()).payload()).isEqualTo("direct");
        // 历史记录仍可读，写入依据快照不可变
        RecordResponse loaded = consentService.read(SUBJ, Purpose.RESEARCH, "rec-1");
        assertThat(loaded.payload()).isEqualTo("before-revoke");
        assertThat(loaded.delegationPath()).hasSize(2);
    }

    @Test
    void revokeEdgeTwiceAndMissingEdgeSemantics() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        delegationService.revoke(new DelegationRevokeRequest("rv1", "e1"));
        expectApi(() -> delegationService.revoke(new DelegationRevokeRequest("rv2", "e1")),
                HttpStatus.CONFLICT, "DELEGATION_ALREADY_REVOKED");
        expectApi(() -> delegationService.revoke(new DelegationRevokeRequest("rv3", "nope")),
                HttpStatus.NOT_FOUND, "DELEGATION_NOT_FOUND");
    }

    @Test
    void revokeEdgeReplaySameRequestIdReturnsOriginalResult() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        DelegationResponse first = delegationService.revoke(new DelegationRevokeRequest("rv1", "e1"));
        DelegationResponse replay = delegationService.revoke(new DelegationRevokeRequest("rv1", "e1"));
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'rv1'")).isEqualTo(1);
    }

    @Test
    void concurrentProcessorWritesAndSubjectRevokeAreSerializedByCommitOrder() throws Exception {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch ready = new CountDownLatch(writers + 1);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String reqId = "wr-" + i;
            String rec = "rec-" + i;
            calls.add(() -> {
                ready.countDown();
                start.await();
                try {
                    write(reqId, "p1", rec, "data", List.of(ref("e1", 1)));
                    return "written";
                } catch (ApiException ex) {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.GONE);
                    return "revoked";
                }
            });
        }
        calls.add(() -> {
            ready.countDown();
            start.await();
            consentService.revoke(new RevokeRequest("rv", SUBJ, Purpose.RESEARCH, 1));
            return "revoke-committed";
        });

        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> c : calls) {
            futures.add(pool.submit(c));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int written = 0;
        int revoked = 0;
        for (Future<String> f : futures) {
            String outcome = f.get(30, TimeUnit.SECONDS);
            if ("written".equals(outcome)) {
                written++;
            } else if ("revoked".equals(outcome)) {
                revoked++;
            }
        }
        pool.shutdown();
        // 撤回应已提交；写入结果取决于提交顺序，但撤回后不得再有写入成功
        assertThat(written + revoked).isEqualTo(writers);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(written);

        // 撤回提交后任何重放/新写入都稳定失败，不能用过期快照穿透
        for (int i = 0; i < writers; i++) {
            String reqId = "wr2-" + i;
            expectApi(() -> write(reqId, "p1", "later-" + reqId, "x", List.of(ref("e1", 1))),
                    HttpStatus.GONE, "CONSENT_REVOKED");
        }
    }

    @Test
    void subjectRevokeCascadesToWholeChainAndNewEpochIsIsolated() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        write("w1", "p1", "rec-1", "epoch1-data", List.of(ref("e1", 1)));

        consentService.revoke(new RevokeRequest("rv1", SUBJ, Purpose.RESEARCH, 1));

        // 旧代整条链失效：处理方写入与链查询均 410
        expectApi(() -> write("w2", "p1", "rec-2", "x", List.of(ref("e1", 1))),
                HttpStatus.GONE, "CONSENT_REVOKED");
        expectApi(() -> delegationService.currentChain(SUBJ, Purpose.RESEARCH, 1, "p1"),
                HttpStatus.GONE, "CONSENT_REVOKED");
        // 旧数据物理保留但不可见
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        expectApi(() -> consentService.read(SUBJ, Purpose.RESEARCH, "rec-1"),
                HttpStatus.GONE, "CONSENT_REVOKED");

        // 新代隔离：旧边不可用，新链需重新委托
        grantEpoch("g2");
        expectApi(() -> write("w3", "p1", "rec-3", "x", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_MISSING");
        delegateFuture("r2", "e2", SUBJ, "p1");
        assertThat(write("w4", "p1", "rec-1", "epoch2-data", List.of(ref("e2", 1))).epoch()).isEqualTo(2);
        assertThat(consentService.read(SUBJ, Purpose.RESEARCH, "rec-1").payload()).isEqualTo("epoch2-data");
    }

    @Test
    void foreignPurposeEdgeCannotBeUsed() {
        consentService.grant(new GrantRequest("g1", SUBJ, Purpose.RESEARCH, GRANT_EXPIRY));
        consentService.grant(new GrantRequest("g2", SUBJ, Purpose.PERSONALIZATION, GRANT_EXPIRY));
        delegationService.delegate(new DelegateRequest(
                "d1", "pe1", SUBJ, Purpose.PERSONALIZATION, SUBJ, "p1",
                GRANT_EXPIRY.minus(Duration.ofDays(1))));

        // 用 PERSONALIZATION 的边为 RESEARCH 写入：在 RESEARCH 有效集中找不到该边
        expectApi(() -> write("w1", "p1", "rec-1", "x", List.of(ref("pe1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_MISSING");
    }

    // ---------- 事务整体回滚 ----------

    @Test
    void failedValidationRollsBackNoRecordAndNoIdempotencyRow() {
        grantEpoch("g1");
        expectApi(() -> write("w1", "p9", "rec-1", "x", List.of()),
                HttpStatus.FORBIDDEN, "DELEGATION_PATH_INVALID");
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w1'")).isZero();

        // 委托失败同样整体回滚，失败不占用 delegation 行
        expectApi(() -> delegateFuture("d1", "eX", "pZ", "pQ"),
                HttpStatus.FORBIDDEN, "DELEGATION_NOT_AUTHORIZED");
        assertThat(count("SELECT COUNT(*) FROM consent_delegation WHERE request_id = 'd1'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'd1'")).isZero();
    }

    @Test
    void payloadConflictLeavesNoIdempotencyRowAndKeyReusableAfterFailure() {
        grantEpoch("g1");
        write("w1", SUBJ, "rec-1", "first", List.of());
        expectApi(() -> write("w2", SUBJ, "rec-1", "different", List.of()),
                HttpStatus.CONFLICT, "RECORD_PAYLOAD_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w2'")).isZero();
        // 失败不占键：同一 requestId 随后可成功用于同参请求
        assertThat(write("w2", SUBJ, "rec-2", "different", List.of()).payload()).isEqualTo("different");
    }

    // ---------- 幂等边界 ----------

    @Test
    void delegateReplayReturnsOriginalAndDifferentParamsConflict() {
        grantEpoch("g1");
        DelegationResponse first = delegateFuture("d1", "e1", SUBJ, "p1");
        DelegationResponse replay = delegateFuture("d1", "e1", SUBJ, "p1");
        assertThat(replay).usingRecursiveComparison().isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM consent_delegation")).isEqualTo(1);

        expectApi(() -> delegationService.delegate(new DelegateRequest(
                        "d1", "e1", SUBJ, Purpose.RESEARCH, SUBJ, "p2",
                        GRANT_EXPIRY.minus(Duration.ofDays(1)))),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
    }

    @Test
    void failedDelegationDoesNotConsumeRequestId() {
        grantEpoch("g1");
        expectApi(() -> delegateFuture("d1", "e1", SUBJ, SUBJ),
                HttpStatus.FORBIDDEN, "DELEGATION_CYCLE");
        // 同一 requestId 随后用于合法委托应成功
        DelegationResponse ok = delegateFuture("d1", "e1", SUBJ, "p1");
        assertThat(ok.toKey()).isEqualTo("p1");
    }

    @Test
    void writeReplayIsRejectedAfterEdgeRevoke() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        write("w1", "p1", "rec-1", "data", List.of(ref("e1", 1)));

        delegationService.revoke(new DelegationRevokeRequest("rv1", "e1"));
        // 同参重放仍须按当前状态拒绝，不能借幂等快照穿透
        expectApi(() -> write("w1", "p1", "rec-1", "data", List.of(ref("e1", 1))),
                HttpStatus.FORBIDDEN, "DELEGATION_EDGE_MISSING");

        // 重新委托到新边后，原 requestId 携带新路径属于异参 -> 409
        delegateFuture("r2", "e2", SUBJ, "p1");
        expectApi(() -> write("w1", "p1", "rec-1", "data", List.of(ref("e2", 1))),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
    }

    @Test
    void sameRequestIdWithChangedPathIsConflict() {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        write("w1", "p1", "rec-1", "data", List.of(ref("e1", 1)));
        // 同 requestId 但改提交版本
        expectApi(() -> write("w1", "p1", "rec-1", "data", List.of(ref("e1", 2))),
                HttpStatus.CONFLICT, "REQUEST_ID_CONFLICT");
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentDelegatesSameEndpointsProduceSingleActiveEdge() throws Exception {
        grantEpoch("g1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "c-" + i;
            String reqId = "cr-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                delegationService.delegate(new DelegateRequest(reqId, key, SUBJ, Purpose.RESEARCH,
                        SUBJ, "p1", GRANT_EXPIRY.minus(Duration.ofDays(1))));
                return key;
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> t : tasks) {
            futures.add(pool.submit(t));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<String> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                assertThat(ex).rootCause().isInstanceOf(ApiException.class);
                ApiException api = (ApiException) rootCause(ex);
                assertThat(api.getCode()).isEqualTo("DELEGATION_EDGE_EXISTS");
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegation WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void concurrentWritesWithSameRequestIdProduceSingleRecord() throws Exception {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<RecordResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return write("same-req", "p1", "rec-x", "same", List.of(ref("e1", 1)));
            });
        }
        List<Future<RecordResponse>> futures = new ArrayList<>();
        for (Callable<RecordResponse> t : tasks) {
            futures.add(pool.submit(t));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<RecordResponse> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS).payload()).isEqualTo("same");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'same-req'")).isEqualTo(1);
    }

    @Test
    void concurrentWritesAndEdgeRevokeNeverLeaveRevokedEdgeBasis() throws Exception {
        grantEpoch("g1");
        delegateFuture("r1", "e1", SUBJ, "p1");
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch ready = new CountDownLatch(writers + 1);
        CountDownLatch start = new CountDownLatch(1);

        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String reqId = "cw-" + i;
            String rec = "rec-" + i;
            calls.add(() -> {
                ready.countDown();
                start.await();
                try {
                    write(reqId, "p1", rec, "data", List.of(ref("e1", 1)));
                    return true;
                } catch (ApiException ex) {
                    assertThat(ex.getCode()).isEqualTo("DELEGATION_EDGE_MISSING");
                    return false;
                }
            });
        }
        calls.add(() -> {
            ready.countDown();
            start.await();
            delegationService.revoke(new DelegationRevokeRequest("rv", "e1"));
            return true;
        });

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> c : calls) {
            futures.add(pool.submit(c));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int written = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                written++;
            }
        }
        pool.shutdown();
        // 撤边本身计 1 次成功；写入成功数介于 0 与 writers 之间，取决于提交顺序
        assertThat(written).isBetween(1, writers + 1);
        // 所有提交的记录都基于同一个 ACTIVE 边快照写入；失败写入不残留
        int records = count("SELECT COUNT(*) FROM consent_record");
        int basisOnE1 = count(
                "SELECT COUNT(*) FROM consent_record WHERE delegation_path LIKE '%\"delegationKey\":\"e1\"%'");
        assertThat(basisOnE1).isEqualTo(records);
        assertThat(count("SELECT COUNT(*) FROM consent_delegation WHERE delegation_key = 'e1' AND status = 'REVOKED'"))
                .isEqualTo(1);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c;
    }
}
