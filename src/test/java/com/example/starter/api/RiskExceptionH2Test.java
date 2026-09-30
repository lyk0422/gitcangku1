package com.example.starter.api;

import com.example.starter.api.dto.ApproveRiskExceptionRequest;
import com.example.starter.api.dto.CreateRiskExceptionRequest;
import com.example.starter.api.dto.ExceptionRef;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MarkRiskRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.RiskExceptionView;
import com.example.starter.support.ApiException;
import com.example.starter.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 依赖风险豁免门禁的真实 H2（MODE=MySQL）测试：
 * 成功锁定、缺豁免/未生效/已撤销/到期/版本不符等 422 分支、恰好到期边界、
 * 两名审批人与申请版本规则、撤销与锁定并发、requestId 重放与异参 409、历史固化。
 */
@SpringBootTest
@Import(MutableClock.Config.class)
class RiskExceptionH2Test {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_entry_exception_approver");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM risk_exception_approval");
        jdbcTemplate.update("DELETE FROM risk_exception");
        jdbcTemplate.update("DELETE FROM artifact_risk");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        ((MutableClock) clock).setInstant(T0);
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private void register(String name, int version, DependencySpec... deps) {
        service.registerArtifact(rid(), new RegisterArtifactRequest(name, version, List.of(deps)));
    }

    private void markHigh(String name, int version) {
        service.markRisk(rid(), new MarkRiskRequest(name, version, "HIGH", "合成高危依赖"));
    }

    private long createException(String name, int version, Instant expiresAt) {
        RiskExceptionView view = service.createRiskException(rid(),
                new CreateRiskExceptionRequest(name, version, "业务阻断窗口内必须发布", expiresAt));
        return view.id();
    }

    private RiskExceptionView approve(long id, String approver, int exceptionVersion) {
        return service.approveRiskException(rid(), id,
                new ApproveRiskExceptionRequest(approver, exceptionVersion));
    }

    private long setupHighRiskScenario() {
        // a:1 -> X[1,1]；X:1 为 HIGH。登记 2 次（风险标记不推进仓库版本）→ 仓库版本 2。
        register("a", 1, dep("X", 1, 1));
        register("X", 1);
        markHigh("X", 1);
        return repositoryVersion();
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private LockRequest lockForA(long expectedVersion, Long... exceptionIds) {
        return new LockRequest("a", 1, expectedVersion,
                java.util.Arrays.stream(exceptionIds).map(ExceptionRef::new).toList());
    }

    // ------------------------------------------------------------------
    // 主流程与 422 门禁
    // ------------------------------------------------------------------

    @Test
    void highRiskDependencyWithoutExceptionReturns422AndWritesNothing() {
        long version = setupHighRiskScenario();

        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("X").contains("HIGH");
                });

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_entry_exception_approver", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE operation = 'CREATE_LOCK'",
                Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(version);
        // 失败不占用 requestId：同一 ID 随后可成功使用。
        String reused = rid();
        assertThatThrownBy(() -> service.createLock(reused, lockForA(version)))
                .isInstanceOf(ApiException.class);
        RiskExceptionView created = service.createRiskException(reused,
                new CreateRiskExceptionRequest("unrelated", 9, "原因", T0.plusSeconds(3600)));
        assertThat(created.id()).isPositive();
    }

    @Test
    void singleApprovalIsNotEffectiveAndLockReturns422() {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));

        RiskExceptionView afterFirst = approve(exceptionId, "jia", 1);
        assertThat(afterFirst.status()).isEqualTo("PENDING");
        assertThat(afterFirst.valid()).isFalse();
        assertThat(afterFirst.approvals()).extracting("approver").containsExactly("jia");

        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version, exceptionId)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void twoDistinctApproversSameVersionUnlockAndFreezeBasisInLock() {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));

        approve(exceptionId, "jia", 1);
        RiskExceptionView approved = approve(exceptionId, "yi", 1);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.valid()).isTrue();
        assertThat(approved.exceptionVersion()).isEqualTo(1);
        assertThat(approved.approvals()).extracting("approver")
                .containsExactly("jia", "yi");

        LockFileResponse lock = service.createLock(rid(), lockForA(version, exceptionId));
        assertThat(lock.entries()).hasSize(2);
        LockEntryResponse xEntry = lock.entries().stream()
                .filter(e -> e.name().equals("X")).findFirst().orElseThrow();
        assertThat(xEntry.version()).isEqualTo(1);
        assertThat(xEntry.riskLevel()).isEqualTo("HIGH");
        assertThat(xEntry.exception()).isNotNull();
        assertThat(xEntry.exception().exceptionId()).isEqualTo(exceptionId);
        assertThat(xEntry.exception().exceptionVersion()).isEqualTo(1);
        assertThat(xEntry.exception().statusAtLock()).isEqualTo("APPROVED");
        assertThat(xEntry.exception().approvers()).containsExactly("jia", "yi");

        // 固化落库：锁条目与审批人快照。
        Map<String, Object> frozen = jdbcTemplate.queryForMap(
                "SELECT risk_level, exception_id, exception_version, status_at_lock "
                        + "FROM lock_file_entry WHERE lock_file_id = ? AND name = 'X'", lock.id());
        assertThat(frozen.get("risk_level")).isEqualTo("HIGH");
        assertThat(((Number) frozen.get("exception_id")).longValue()).isEqualTo(exceptionId);
        assertThat(frozen.get("status_at_lock")).isEqualTo("APPROVED");
        List<String> approvers = jdbcTemplate.queryForList(
                "SELECT approver FROM lock_entry_exception_approver WHERE lock_file_id = ? ORDER BY seq",
                String.class, lock.id());
        assertThat(approvers).containsExactly("jia", "yi");
    }

    @Test
    void sameApproverTwiceDoesNotCountAndCannotApproveAfterApproved() {
        setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));

        approve(exceptionId, "jia", 1);
        assertThatThrownBy(() -> approve(exceptionId, "jia", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 换 requestId 也不能绕过：唯一审批人计数仍为 1。
        approve(exceptionId, "yi", 1);
        assertThat(service.getRiskException(exceptionId).status()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> approve(exceptionId, "bing", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void revokeOnlyAffectsNewLocksAndHistoricalLockKeepsBasis() {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);
        LockFileResponse first = service.createLock(rid(), lockForA(version, exceptionId));

        RiskExceptionView revoked = service.revokeRiskException(rid(), exceptionId);
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.valid()).isFalse();

        // 历史锁文件不改写：仍为 HIGH + APPROVED + 两名审批人。
        LockFileResponse historical = service.getLock(first.id());
        LockEntryResponse xEntry = historical.entries().stream()
                .filter(e -> e.name().equals("X")).findFirst().orElseThrow();
        assertThat(xEntry.riskLevel()).isEqualTo("HIGH");
        assertThat(xEntry.exception().statusAtLock()).isEqualTo("APPROVED");
        assertThat(xEntry.exception().approvers()).containsExactly("jia", "yi");

        // 新锁定携带已撤销豁免 → 422。
        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version, exceptionId)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("撤销");
                });
        // 无豁免同样 422。
        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 重复撤销 409，撤销未生效豁免 422。
        assertThatThrownBy(() -> service.revokeRiskException(rid(), exceptionId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void exceptionExactlyAtExpiryIsInvalidAndBoundaryBeforeExpiryWorks() {
        // 到期时刻 = T0+2s；提前 1 秒仍可用。
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(2));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);

        ((MutableClock) clock).setInstant(T0.plusSeconds(1));
        LockFileResponse before = service.createLock(rid(), lockForA(version, exceptionId));
        assertThat(before.id()).isPositive();

        // 另一个豁免，恰好在到期时刻锁定 → 失效 422。
        long exactId = createException("X", 1, T0.plusSeconds(3));
        approve(exactId, "jia", 1);
        approve(exactId, "yi", 1);
        ((MutableClock) clock).setInstant(T0.plusSeconds(3));
        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version, exactId)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("到期");
                });
        assertThat(service.getRiskException(exactId).valid()).isFalse();
        assertThat(service.listLocks()).hasSize(1);
    }

    @Test
    void creatingExceptionWithPastExpiryReturns422() {
        setupHighRiskScenario();
        ((MutableClock) clock).setInstant(T0.plusSeconds(10));
        assertThatThrownBy(() -> service.createRiskException(rid(),
                new CreateRiskExceptionRequest("X", 1, "过期原因", T0.plusSeconds(9))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 恰好等于当前时刻同样不合法。
        assertThatThrownBy(() -> service.createRiskException(rid(),
                new CreateRiskExceptionRequest("X", 1, "原因", T0.plusSeconds(10))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void exceptionForAnotherVersionIsRejected() {
        // a2:1 -> X[2,2]，X:1/X:2 均为 HIGH；豁免仅适用 X@1。
        register("a2", 1, dep("X", 2, 2));
        register("X", 2);
        markHigh("X", 2);
        long version = setupHighRiskScenario();
        long exceptionForV1 = createException("X", 1, T0.plusSeconds(3600));
        approve(exceptionForV1, "jia", 1);
        approve(exceptionForV1, "yi", 1);

        // 对解析到 X@2 的锁定引用 X@1 豁免 → 版本不符 422（X@1.1 仍被拒）。
        LockRequest requestForV2 = new LockRequest("a2", 1, version,
                List.of(new ExceptionRef(exceptionForV1)));
        assertThatThrownBy(() -> service.createLock(rid(), requestForV2))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("不符");
                });
        assertThat(service.listLocks()).isEmpty();

        // 不存在的豁免 ID → 422。
        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version, 999999L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void duplicateAndUnneededExceptionReferencesReturn422() {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);

        LockRequest duplicated = new LockRequest("a", 1, version,
                List.of(new ExceptionRef(exceptionId), new ExceptionRef(exceptionId)));
        assertThatThrownBy(() -> service.createLock(rid(), duplicated))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 低风险版本引用豁免 → 多余豁免 422（风险标记不推进仓库版本）。
        service.markRisk(rid(), new MarkRiskRequest("X", 1, "LOW", null));
        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version, exceptionId)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 低风险本身不阻断。
        LockFileResponse lowRiskLock = service.createLock(rid(), lockForA(version));
        assertThat(lowRiskLock.entries()).extracting("name", "riskLevel")
                .contains(tuple("X", "LOW"));
    }

    @Test
    void criticalRiskAlsoBlocksLock() {
        long version = setupHighRiskScenario();
        service.markRisk(rid(), new MarkRiskRequest("X", 1, "CRITICAL", null));
        assertThatThrownBy(() -> service.createLock(rid(), lockForA(version)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    // ------------------------------------------------------------------
    // 申请版本：修订后旧审批作废，审批必须针对同一版本
    // ------------------------------------------------------------------

    @Test
    void reviseBumpsVersionAndOldApprovalsReset() {
        setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(60));
        approve(exceptionId, "jia", 1);

        RiskExceptionView revised = service.reviseRiskException(rid(), exceptionId,
                new com.example.starter.api.dto.ReviseRiskExceptionRequest(
                        "延长到期并补充原因", T0.plusSeconds(3600)));
        assertThat(revised.exceptionVersion()).isEqualTo(2);
        assertThat(revised.status()).isEqualTo("PENDING");
        assertThat(revised.valid()).isFalse();
        assertThat(revised.approvals()).isEmpty();

        // 针对旧版本 1 的审批 → 409。
        assertThatThrownBy(() -> approve(exceptionId, "yi", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 两名审批人确认新版本 2 后生效。
        approve(exceptionId, "yi", 2);
        RiskExceptionView approved = approve(exceptionId, "jia", 2);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvals()).extracting("approver", "exceptionVersion")
                .containsExactlyInAnyOrder(tuple("jia", 2), tuple("yi", 2));
    }

    @Test
    void cannotReviseApprovedOrRevokedException() {
        setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(60));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);
        assertThatThrownBy(() -> service.reviseRiskException(rid(), exceptionId,
                new com.example.starter.api.dto.ReviseRiskExceptionRequest("x", T0.plusSeconds(120))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        service.revokeRiskException(rid(), exceptionId);
        assertThatThrownBy(() -> service.reviseRiskException(rid(), exceptionId,
                new com.example.starter.api.dto.ReviseRiskExceptionRequest("x", T0.plusSeconds(120))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    // ------------------------------------------------------------------
    // 幂等重放与异参 409
    // ------------------------------------------------------------------

    @Test
    void lockRequestIdReplaysSameSnapshotAndChangedExceptionsConflict() {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);

        String lockRid = rid();
        LockFileResponse first = service.createLock(lockRid, lockForA(version, exceptionId));
        LockFileResponse replay = service.createLock(lockRid, lockForA(version, exceptionId));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.entries()).isEqualTo(first.entries());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);

        // 同 requestId 更换豁免清单 → 异参 409。
        assertThatThrownBy(() -> service.createLock(lockRid, lockForA(version)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 仓库前进（登记新版本 X:2，解析结果随之变化）后，同 requestId 携带新期望版本仍 409，
        // 且原始重放始终返回首次的依赖清单与豁免快照。
        register("X", 2);
        long newVersion = repositoryVersion();
        LockRequest changed = new LockRequest("a", 1, newVersion, List.of(new ExceptionRef(exceptionId)));
        assertThatThrownBy(() -> service.createLock(lockRid, changed))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        LockFileResponse originalAgain = service.createLock(lockRid, lockForA(version, exceptionId));
        assertThat(originalAgain.entries()).isEqualTo(first.entries());
    }

    @Test
    void approvalAndRiskMarkingAreIdempotentByRequestId() {
        setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(60));

        String approveRid = rid();
        var approveRequest = new ApproveRiskExceptionRequest("jia", 1);
        RiskExceptionView first = service.approveRiskException(approveRid, exceptionId, approveRequest);
        RiskExceptionView replay = service.approveRiskException(approveRid, exceptionId, approveRequest);
        assertThat(replay.approvals()).isEqualTo(first.approvals());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM risk_exception_approval WHERE exception_id = ?",
                Integer.class, exceptionId)).isEqualTo(1);

        // 风险标记同参重放，不重复推进版本。
        long before = repositoryVersion();
        String markRid = rid();
        var markRequest = new MarkRiskRequest("X", 1, "HIGH", "合成高危依赖");
        service.markRisk(markRid, markRequest);
        service.markRisk(markRid, markRequest);
        assertThat(repositoryVersion()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // 历史查询：风险级别与豁免依据固化
    // ------------------------------------------------------------------

    @Test
    void historicalLockKeepsRiskLevelWhenMarkingChangesLater() {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);
        LockFileResponse lock = service.createLock(rid(), lockForA(version, exceptionId));

        // 事后将风险降为 LOW，历史锁条目仍固化 HIGH 及豁免依据。
        service.markRisk(rid(), new MarkRiskRequest("X", 1, "LOW", "误报修正"));
        LockEntryResponse historicalX = service.getLock(lock.id()).entries().stream()
                .filter(e -> e.name().equals("X")).findFirst().orElseThrow();
        assertThat(historicalX.riskLevel()).isEqualTo("HIGH");
        assertThat(historicalX.exception().exceptionId()).isEqualTo(exceptionId);

        // 当前视图反映最新状态。
        assertThat(service.listRisks()).extracting("name", "version", "riskLevel")
                .containsExactly(tuple("X", 1, "LOW"));
        // 豁免查询展示适用版本、审批人与当前状态。
        RiskExceptionView view = service.listRiskExceptions().get(0);
        assertThat(view.dependencyName()).isEqualTo("X");
        assertThat(view.dependencyVersion()).isEqualTo(1);
        assertThat(view.approvals()).extracting("approver").containsExactly("jia", "yi");
    }

    // ------------------------------------------------------------------
    // 并发：豁免撤销与锁定同时到达，按提交状态判定，不得绕过门禁
    // ------------------------------------------------------------------

    @Test
    void concurrentRevokeAndLockIsDecidedByCommitOrder() throws Exception {
        long version = setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));
        approve(exceptionId, "jia", 1);
        approve(exceptionId, "yi", 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Callable<Object> lockTask = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.createLock(rid(), lockForA(version, exceptionId));
                } catch (ApiException e) {
                    return e;
                }
            };
            Callable<Object> revokeTask = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.revokeRiskException(rid(), exceptionId);
            };

            List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, revokeTask));
            Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
            Object revokeOutcome = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(revokeOutcome).isInstanceOf(RiskExceptionView.class);
            assertThat(((RiskExceptionView) revokeOutcome).status()).isEqualTo("REVOKED");

            if (lockOutcome instanceof LockFileResponse lock) {
                // 锁定先提交：锁存在且依据完整；之后的新锁必须被门禁拒绝，历史锁不改写。
                LockEntryResponse xEntry = lock.entries().stream()
                        .filter(e -> e.name().equals("X")).findFirst().orElseThrow();
                assertThat(xEntry.riskLevel()).isEqualTo("HIGH");
                assertThat(xEntry.exception().approvers()).containsExactly("jia", "yi");
                assertThatThrownBy(() -> service.createLock(rid(), lockForA(version, exceptionId)))
                        .isInstanceOfSatisfying(ApiException.class,
                                ex -> assertThat(ex.getStatus()).isEqualTo(422));
                LockEntryResponse historicalX = service.getLock(lock.id()).entries().stream()
                        .filter(e -> e.name().equals("X")).findFirst().orElseThrow();
                assertThat(historicalX.exception().statusAtLock()).isEqualTo("APPROVED");
            } else {
                // 撤销先提交：锁 422 且无半成品。
                assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDistinctApproversExactlyTwoSucceed() throws Exception {
        setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String approver = "approver-" + i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.approveRiskException(rid(), exceptionId,
                            new ApproveRiskExceptionRequest(approver, 1));
                    return 200;
                } catch (ApiException e) {
                    return e.getStatus();
                }
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int success = 0;
        int conflict = 0;
        for (Future<Integer> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS);
            assertThat(status).isIn(200, 409);
            if (status == 200) {
                success++;
            } else {
                conflict++;
            }
        }
        assertThat(success).isEqualTo(2);
        assertThat(conflict).isEqualTo(2);
        assertThat(service.getRiskException(exceptionId).status()).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM risk_exception_approval WHERE exception_id = ?",
                Integer.class, exceptionId)).isEqualTo(2);
    }

    @Test
    void concurrentSameApproverOnlyOneApprovalPersists() throws Exception {
        setupHighRiskScenario();
        long exceptionId = createException("X", 1, T0.plusSeconds(3600));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.approveRiskException(rid(), exceptionId,
                            new ApproveRiskExceptionRequest("jia", 1));
                    return 200;
                } catch (ApiException e) {
                    return e.getStatus();
                }
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        int success = 0;
        for (Future<Integer> future : futures) {
            if (future.get(30, TimeUnit.SECONDS) == 200) {
                success++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM risk_exception_approval WHERE exception_id = ?",
                Integer.class, exceptionId)).isEqualTo(1);
    }
}
