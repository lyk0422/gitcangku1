package com.example.starter.api;

import com.example.starter.api.dto.ExceptionApproveRequest;
import com.example.starter.api.dto.ExceptionCreateRequest;
import com.example.starter.api.dto.ExceptionResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
 * 依赖风险豁免的真实 H2（MODE=MySQL）业务测试：
 * HIGH 门禁、双人审批、到期边界（可控时钟）、撤销与锁定并发、幂等重放与历史固化。
 *
 * <p>版本模型为正整数：合成场景中 X@1.0 记为 X 版本 1，X@1.1 记为 X 版本 2。
 */
@SpringBootTest
class RiskExceptionH2Test {

    /** 测试用可控 UTC 时钟：可随时推进，避免靠睡眠判断到期。 */
    static class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_exception");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM risk_exception_approval");
        jdbcTemplate.update("DELETE FROM risk_exception");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        ((MutableClock) clock).instant = Instant.parse("2026-01-01T00:00:00Z");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    /** 登记 A:1 -> X[1,1] 与 X:1，将 X:1 标记 HIGH，返回当前仓库版本（=3）。 */
    private long seedSyntheticHighRisk() {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("A", 1, List.of(dep("X", 1, 1))));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("X", 1, null, List.of()));
        service.markRisk(requestId(), "X", 1,
                new com.example.starter.api.dto.MarkRiskRequest("HIGH"));
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private ExceptionResponse createAndApproveExceptionForX1() {
        Instant expiresAt = clock.instant().plus(Duration.ofHours(1));
        ExceptionResponse created = service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, expiresAt, "大客户兼容性窗口，无法立即升级"));
        service.approveException(created.id(), new ExceptionApproveRequest("jia"));
        ExceptionResponse approved = service.approveException(
                created.id(), new ExceptionApproveRequest("yi"));
        return approved;
    }

    private static LockRequest lockRequest(long expectedVersion, Long... exceptionIds) {
        return new LockRequest("A", 1, expectedVersion, List.of(exceptionIds));
    }

    // ------------------------------------------------------------------
    // 合成验收主流程
    // ------------------------------------------------------------------

    @Test
    void highRiskDependencyWithoutExceptionReturns422AndNoHalfLock() {
        long repoVersion = seedSyntheticHighRisk();

        assertThatThrownBy(() -> service.createLock(requestId(), lockRequest(repoVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("X:1").contains("HIGH");
                        });
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_exception", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(repoVersion);
    }

    @Test
    void twoDistinctApproversForExactVersionUnblockLockAndSnapshotIsFrozen() {
        long repoVersion = seedSyntheticHighRisk();

        Instant expiresAt = clock.instant().plus(Duration.ofHours(1));
        ExceptionResponse created = service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, expiresAt, "大客户兼容性窗口"));
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.approvers()).isEmpty();

        ExceptionResponse first = service.approveException(
                created.id(), new ExceptionApproveRequest("jia"));
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(first.approvers()).containsExactly("jia");

        // 单人审批后仍被门禁拒绝。
        assertThatThrownBy(() -> service.createLock(requestId(), lockRequest(repoVersion, created.id())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        ExceptionResponse approved = service.approveException(
                created.id(), new ExceptionApproveRequest("yi", created.contentVersion()));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvers()).containsExactly("jia", "yi");
        assertThat(approved.approvedAt()).isEqualTo(clock.instant());

        LockFileResponse lock = service.createLock(requestId(), lockRequest(repoVersion, created.id()));
        assertThat(lock.entries()).extracting("name", "version", "riskLevel", "exceptionId")
                .containsExactly(
                        tuple("A", 1, "NONE", null),
                        tuple("X", 1, "HIGH", created.id()));
        assertThat(lock.exceptions()).hasSize(1);
        assertThat(lock.exceptions().get(0).exceptionId()).isEqualTo(created.id());
        assertThat(lock.exceptions().get(0).dependencyName()).isEqualTo("X");
        assertThat(lock.exceptions().get(0).dependencyVersion()).isEqualTo(1);
        assertThat(lock.exceptions().get(0).status()).isEqualTo("APPROVED");
        assertThat(lock.exceptions().get(0).approvers()).containsExactly("jia", "yi");

        // 撤销豁免：新锁定被拒，历史锁文件不改写。
        ExceptionResponse revoked = service.revokeException(created.id(),
                new com.example.starter.api.dto.ExceptionRevokeRequest("sec"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> service.createLock(requestId(), lockRequest(repoVersion, created.id())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.entries()).isEqualTo(lock.entries());
        assertThat(historical.exceptions()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(lock.exceptions());
    }

    @Test
    void exceptionForX10DoesNotUnlockX11() {
        // A:1 -> X[1,1]（X@1.0=版本1），X:1 HIGH；另有 A:2 -> X[2,2]（X@1.1=版本2），X:2 HIGH。
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("A", 1, List.of(dep("X", 1, 1))));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("A", 2, List.of(dep("X", 2, 2))));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("X", 1, "HIGH", List.of()));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("X", 2, "HIGH", List.of()));
        long repoVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        ExceptionResponse exceptionX1 = createAndApproveExceptionForX1();

        // 同一豁免仅适用 X@1.0：锁 A:1 成功。
        LockFileResponse lockA1 = service.createLock(requestId(),
                lockRequest(repoVersion, exceptionX1.id()));
        assertThat(lockA1.entries()).extracting("name", "version")
                .containsExactly(tuple("A", 1), tuple("X", 1));

        // X@1.1：拿 X@1.0 的豁免去锁 → 版本不符 422；不带豁免 → 缺少豁免 422。
        LockRequest lockA2WithWrongException = new LockRequest(
                "A", 2, repoVersion, List.of(exceptionX1.id()));
        assertThatThrownBy(() -> service.createLock(requestId(), lockA2WithWrongException))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("版本不符");
                        });
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("A", 2, repoVersion, List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 到期边界（可控时钟，恰好到期即失效）
    // ------------------------------------------------------------------

    @Test
    void exceptionExactlyAtExpiryMomentIsNoLongerValid() {
        long repoVersion = seedSyntheticHighRisk();
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(10));
        ExceptionResponse exception = service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, expiresAt, "临时窗口"));
        service.approveException(exception.id(), new ExceptionApproveRequest("jia"));
        service.approveException(exception.id(), new ExceptionApproveRequest("yi"));

        // 到期前一刻：有效。
        mutableClock().advance(Duration.ofMinutes(10).minusNanos(1));
        LockFileResponse lock = service.createLock(requestId(),
                lockRequest(repoVersion, exception.id()));
        assertThat(lock.id()).isPositive();

        // 新锁定：恰好到期时刻 → 422。
        mutableClock().advance(Duration.ofNanos(1));
        assertThat(clock.instant()).isEqualTo(expiresAt);
        assertThatThrownBy(() -> service.createLock(requestId(),
                lockRequest(repoVersion, exception.id())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("到期");
                        });

        // 到期之后仍 422，且无新增锁。
        mutableClock().advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> service.createLock(requestId(),
                lockRequest(repoVersion, exception.id())))
                .isInstanceOf(ApiException.class);
        assertThat(service.listLocks()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 审批/撤销状态与非法输入
    // ------------------------------------------------------------------

    @Test
    void sameApproverCannotApproveTwiceAndContentVersionMustMatch() {
        ExceptionResponse exception = createAndApproveExceptionForX1();

        // 已生效后再审批 → 422。
        assertThatThrownBy(() -> service.approveException(
                exception.id(), new ExceptionApproveRequest("bing")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 审批不存在的豁免 → 404。
        assertThatThrownBy(() -> service.approveException(
                99999L, new ExceptionApproveRequest("jia")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void pendingExceptionCannotBeRevokedAndDoubleRevokeReturns422() {
        long repoVersion = seedSyntheticHighRisk();
        ExceptionResponse exception = service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, clock.instant().plusSeconds(3600), "r"));

        assertThatThrownBy(() -> service.revokeException(exception.id(),
                new com.example.starter.api.dto.ExceptionRevokeRequest("sec")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        service.approveException(exception.id(), new ExceptionApproveRequest("jia"));
        service.approveException(exception.id(), new ExceptionApproveRequest("yi"));
        service.revokeException(exception.id(),
                new com.example.starter.api.dto.ExceptionRevokeRequest("sec"));
        assertThatThrownBy(() -> service.revokeException(exception.id(),
                new com.example.starter.api.dto.ExceptionRevokeRequest("sec")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 撤销后仓库版本不变（撤销不产生新版本），但门禁状态已是 REVOKED。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(repoVersion);
    }

    @Test
    void invalidExceptionInputsAreRejected() {
        seedSyntheticHighRisk();
        // 到期时刻在过去 → 422。
        assertThatThrownBy(() -> service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, clock.instant().minusSeconds(1), "late")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 空原因 → 400。
        assertThatThrownBy(() -> service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, clock.instant().plusSeconds(60), " ")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 锁定引用不存在的豁免 → 422。
        long repoVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("A", 1, repoVersion, List.of(88888L))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        // 豁免 ID 重复 → 422。
        ExceptionResponse exception = createAndApproveExceptionForX1();
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("A", 1, repoVersion, List.of(exception.id(), exception.id()))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void mediumRiskPassesWithoutExceptionButCriticalIsBlocked() {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("app", 1, List.of(dep("m", 1, 1), dep("c", 1, 1))));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("m", 1, "MEDIUM", List.of()));
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest("c", 1, "CRITICAL", List.of()));
        long repoVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        // MEDIUM 不触发门禁，CRITICAL 触发。
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion, List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("c:1").contains("CRITICAL");
                        });
    }

    // ------------------------------------------------------------------
    // 幂等：同 requestId 重放快照与豁免依据，改参数 409
    // ------------------------------------------------------------------

    @Test
    void lockReplayReturnsOriginalManifestAndExceptionSnapshot() {
        long repoVersion = seedSyntheticHighRisk();
        ExceptionResponse exception = createAndApproveExceptionForX1();
        String rid = requestId();
        LockRequest request = lockRequest(repoVersion, exception.id());
        LockFileResponse first = service.createLock(rid, request);

        LockFileResponse replay = service.createLock(rid, request);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.entries()).isEqualTo(first.entries());
        assertThat(replay.exceptions()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.exceptions());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_exception", Integer.class))
                .isEqualTo(1);

        // 撤销后同参重放仍返回首次的成功快照（重放不重新过门禁）。
        service.revokeException(exception.id(),
                new com.example.starter.api.dto.ExceptionRevokeRequest("sec"));
        LockFileResponse replayAfterRevoke = service.createLock(rid, request);
        assertThat(replayAfterRevoke.id()).isEqualTo(first.id());
        assertThat(replayAfterRevoke.exceptions().get(0).status()).isEqualTo("APPROVED");
    }

    @Test
    void sameRequestIdWithDifferentExceptionListReturns409() {
        long repoVersion = seedSyntheticHighRisk();
        ExceptionResponse e1 = createAndApproveExceptionForX1();
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, lockRequest(repoVersion, e1.id()));
        assertThat(first.id()).isPositive();

        // 同一 requestId 改参数（去掉豁免）→ 409。
        assertThatThrownBy(() -> service.createLock(rid, lockRequest(repoVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void concurrentSameLockRequestIdResolvesToSingleLock() throws Exception {
        int threads = 6;
        long repoVersion = seedSyntheticHighRisk();
        ExceptionResponse exception = createAndApproveExceptionForX1();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        LockRequest request = lockRequest(repoVersion, exception.id());

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.createLock(rid, request);
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        pool.shutdown();

        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            assertThat(outcome).isInstanceOf(LockFileResponse.class);
            assertThat(((LockFileResponse) outcome).id())
                    .isEqualTo(((LockFileResponse) futures.get(0).get(30, TimeUnit.SECONDS)).id());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_exception", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发：双人同时批准；撤销与锁定竞争
    // ------------------------------------------------------------------

    @Test
    void concurrentApprovalsByTwoDistinctApproversTakeEffectExactlyOnce() throws Exception {
        seedSyntheticHighRisk();
        ExceptionResponse exception = service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, clock.instant().plusSeconds(3600), "r"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> jia = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.approveException(exception.id(), new ExceptionApproveRequest("jia"));
        };
        Callable<Object> yi = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.approveException(exception.id(), new ExceptionApproveRequest("yi"));
        };
        List<Future<Object>> futures = pool.invokeAll(List.of(jia, yi));
        pool.shutdown();
        for (Future<Object> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isInstanceOf(ExceptionResponse.class);
        }

        ExceptionResponse reloaded = service.getException(exception.id());
        assertThat(reloaded.status()).isEqualTo("APPROVED");
        assertThat(reloaded.approvers()).containsExactlyInAnyOrder("jia", "yi");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM risk_exception_approval WHERE exception_id = ?",
                Integer.class, exception.id())).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateApproverIsRejectedByUniqueConstraint() throws Exception {
        seedSyntheticHighRisk();
        ExceptionResponse exception = service.createException(requestId(),
                new ExceptionCreateRequest("X", 1, clock.instant().plusSeconds(3600), "r"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> task = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.approveException(exception.id(), new ExceptionApproveRequest("jia"));
            } catch (ApiException e) {
                return e;
            }
        };
        List<Future<Object>> futures = pool.invokeAll(List.of(task, task));
        pool.shutdown();

        int success = 0;
        int rejected = 0;
        for (Future<Object> f : futures) {
            Object outcome = f.get(30, TimeUnit.SECONDS);
            if (outcome instanceof ExceptionResponse) {
                success++;
            } else {
                assertThat(outcome).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
                rejected++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM risk_exception_approval WHERE exception_id = ?",
                Integer.class, exception.id())).isEqualTo(1);
        assertThat(service.getException(exception.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void revokeAndLockCompeteResultFollowsCommitState() throws Exception {
        long repoVersion = seedSyntheticHighRisk();
        ExceptionResponse exception = createAndApproveExceptionForX1();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRid = requestId();
        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRid, lockRequest(repoVersion, exception.id()));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> revokeTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.revokeException(exception.id(),
                    new com.example.starter.api.dto.ExceptionRevokeRequest("sec"));
        };
        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, revokeTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object revokeOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(revokeOutcome).isInstanceOf(ExceptionResponse.class);
        assertThat(((ExceptionResponse) revokeOutcome).status()).isEqualTo("REVOKED");

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁定先提交：历史锁固化 APPROVED 豁免，撤销不改写。
            assertThat(lock.entries()).extracting("name", "version", "exceptionId")
                    .containsExactly(tuple("A", 1, null), tuple("X", 1, exception.id()));
            assertThat(lock.exceptions()).hasSize(1);
            assertThat(lock.exceptions().get(0).status()).isEqualTo("APPROVED");
            assertThat(service.getException(exception.id()).status()).isEqualTo("REVOKED");
            // 撤销后不能再产生新锁。
            assertThatThrownBy(() -> service.createLock(requestId(),
                    lockRequest(repoVersion, exception.id())))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(422));
            assertThat(service.listLocks()).hasSize(1);
        } else {
            // 撤销先提交：门禁按 REVOKED 判定，422 且无半份锁。
            assertThat(lockOutcome).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(422));
            assertThat(service.listLocks()).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                    .isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_exception", Integer.class))
                    .isZero();
        }
    }

    // ------------------------------------------------------------------
    // 查询视图
    // ------------------------------------------------------------------

    @Test
    void exceptionAndLockHistoryViewsExposeStatusApproversAndApplicableVersion() {
        long repoVersion = seedSyntheticHighRisk();
        ExceptionResponse exception = createAndApproveExceptionForX1();
        service.createLock(requestId(), lockRequest(repoVersion, exception.id()));

        ExceptionResponse view = service.getException(exception.id());
        assertThat(view.dependencyName()).isEqualTo("X");
        assertThat(view.dependencyVersion()).isEqualTo(1);
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.approvers()).containsExactly("jia", "yi");
        assertThat(service.listExceptions()).hasSize(1);

        LockFileResponse historical = service.listLocks().get(0);
        assertThat(historical.entries()).extracting("name", "version", "riskLevel", "exceptionId")
                .containsExactly(
                        tuple("A", 1, "NONE", null),
                        tuple("X", 1, "HIGH", exception.id()));
        assertThat(historical.exceptions().get(0).dependencyVersion()).isEqualTo(1);
        assertThat(historical.exceptions().get(0).approvers()).containsExactly("jia", "yi");
    }
}
