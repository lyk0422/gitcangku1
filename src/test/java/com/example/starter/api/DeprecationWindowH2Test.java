package com.example.starter.api;

import com.example.starter.api.dto.DeprecationBatchRequest;
import com.example.starter.api.dto.DeprecationViolationView;
import com.example.starter.api.dto.DeprecationWindowRequest;
import com.example.starter.api.dto.DeprecationWindowResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.support.ApiException;
import com.example.starter.support.DeprecationViolationException;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
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
 * 弃用窗口的 H2（MODE=MySQL）数据库测试：登记/撤销/批量原子性、锁定裁决边界、
 * 历史锁不可回写、重解析、幂等与并发，全部使用可控时钟（UTC）。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class DeprecationWindowH2Test {

    private static final Instant T0 = TestClockConfiguration.BASE;

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("DELETE FROM lock_deprecation_warning");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_deprecation");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
        clock.setInstant(T0);
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void register(String name, int version) {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest(name, version, List.of()));
    }

    private void register(String name, int version, String depName, int min, int max) {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest(name, version,
                        List.of(new com.example.starter.api.dto.DependencySpec(depName, min, max))));
    }

    private long repositoryVersion() {
        Long v = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        return v == null ? 0 : v;
    }

    private static DeprecationWindowRequest window(String name, int version,
                                                   Instant effectiveAt, int replacement) {
        return new DeprecationWindowRequest(name, version, effectiveAt, replacement, "弃用说明");
    }

    // ------------------------------------------------------------------
    // 主流程：登记、撤销、查询
    // ------------------------------------------------------------------

    @Test
    void registerDeprecationBatchPersistsAndAdvancesRepositoryVersion() {
        register("lib", 1);
        register("lib", 2);
        long before = repositoryVersion();

        List<DeprecationWindowResponse> responses = service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 2))));

        assertThat(responses).hasSize(1);
        DeprecationWindowResponse response = responses.get(0);
        assertThat(response.name()).isEqualTo("lib");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.effectiveAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(response.replacementVersion()).isEqualTo(2);
        assertThat(response.revoked()).isFalse();
        assertThat(response.revokedAt()).isNull();
        assertThat(response.createdAt()).isEqualTo(T0);
        assertThat(repositoryVersion()).isEqualTo(before + 1);

        // 查询不修改状态。
        List<DeprecationWindowResponse> listed = service.listDeprecations();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).id()).isEqualTo(response.id());
        assertThat(repositoryVersion()).isEqualTo(before + 1);
    }

    @Test
    void revokeBeforeEffectiveKeepsRecordAndRemovesAdjudication() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));

        DeprecationWindowResponse revoked = service.revokeDeprecation(requestId(), "lib", 1);
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.revokedAt()).isEqualTo(T0);

        // 撤销记录保留，历史可查。
        List<DeprecationWindowResponse> all = service.listDeprecations();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).revoked()).isTrue();
        assertThat(all.get(0).revokedAt()).isEqualTo(T0);

        // 撤销后锁定不再产生告警。
        register("app", 1, "lib", 1, 1);
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.deprecationWarnings()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 失败分支：登记校验
    // ------------------------------------------------------------------

    @Test
    void effectiveAtInPastReturns422() {
        register("lib", 1);
        register("lib", 2);
        clock.setInstant(T0.plusSeconds(60));
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(30), 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isZero();
    }

    @Test
    void missingReplacementReturns422() {
        register("lib", 1);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 9)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void withdrawnReplacementReturns422() {
        register("lib", 1);
        register("lib", 2);
        service.withdrawArtifact(requestId(), "lib", 2);
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void replacementCycleReturns422() {
        register("lib", 1);
        register("lib", 2);
        // 同一批内 1->2 且 2->1 成环。
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 2),
                        window("lib", 2, T0.plusSeconds(3600), 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void cycleWithExistingWindowReturns422() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));
        // 存量 1->2，再登记 2->1 成环。
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 2, T0.plusSeconds(3600), 1)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void batchFailureWritesNothingAndConsumesNoRequestId() {
        register("lib", 1);
        register("lib", 2);
        long before = repositoryVersion();
        String rid = requestId();
        // 第一条合法、第二条替代不存在：整批不写入。
        assertThatThrownBy(() -> service.registerDeprecations(rid,
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 2),
                        window("lib", 2, T0.plusSeconds(3600), 9)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(before);

        // 失败不占键：同一 requestId 可重新提交合法批次。
        List<DeprecationWindowResponse> ok = service.registerDeprecations(rid,
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(3600), 2))));
        assertThat(ok).hasSize(1);
    }

    @Test
    void duplicateActiveWindowReturns409() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));
        assertThatThrownBy(() -> service.registerDeprecations(requestId(),
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(7200), 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void revokeAfterEffectiveReturns409() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(60), 2))));
        clock.advance(Duration.ofSeconds(60));
        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 记录未被撤销。
        assertThat(service.listDeprecations().get(0).revoked()).isFalse();
    }

    @Test
    void revokeMissingWindowReturns404() {
        register("lib", 1);
        assertThatThrownBy(() -> service.revokeDeprecation(requestId(), "lib", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 锁定裁决：告警、生效 422、历史固化
    // ------------------------------------------------------------------

    @Test
    void lockBeforeEffectiveEmitsWarningAndPersistsSnapshot() {
        register("app", 1, "lib", 1, 1);
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));

        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        assertThat(lock.deprecationWarnings()).hasSize(1);
        assertThat(lock.deprecationWarnings().get(0).code()).isEqualTo("DEPRECATION_WARNING");
        assertThat(lock.deprecationWarnings().get(0).name()).isEqualTo("lib");
        assertThat(lock.deprecationWarnings().get(0).version()).isEqualTo(1);
        assertThat(lock.deprecationWarnings().get(0).replacementVersion()).isEqualTo(2);
        assertThat(lock.deprecationWarnings().get(0).effectiveAt())
                .isEqualTo(T0.plusSeconds(3600));

        // 告警已固化到数据库，历史查询一致。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_deprecation_warning WHERE lock_file_id = ?",
                Integer.class, lock.id())).isEqualTo(1);
        assertThat(service.getLock(lock.id()).deprecationWarnings())
                .isEqualTo(lock.deprecationWarnings());
    }

    @Test
    void lockAfterEffectiveFails422WithSortedViolations() {
        register("app", 1, "blib", 1, 1);
        register("app", 2, "alib", 1, 1);
        // 根 app:1 依赖 blib；构造两个制品的弃用窗口验证排序。
        register("alib", 1);
        register("alib", 2);
        register("blib", 1);
        register("blib", 2);
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 3,
                List.of(new com.example.starter.api.dto.DependencySpec("alib", 1, 1),
                        new com.example.starter.api.dto.DependencySpec("blib", 1, 1))));
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("blib", 1, T0.plusSeconds(60), 2),
                window("alib", 1, T0.plusSeconds(60), 2))));
        clock.advance(Duration.ofSeconds(60));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 3, repositoryVersion())))
                .isInstanceOfSatisfying(DeprecationViolationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    List<DeprecationViolationView> violations = e.getViolations();
                    assertThat(violations).extracting("name", "version")
                            .containsExactly(tuple("alib", 1), tuple("blib", 1));
                    assertThat(violations).allSatisfy(v -> {
                        assertThat(v.replacementVersion()).isEqualTo(2);
                        assertThat(v.effectiveAt()).isEqualTo(T0.plusSeconds(60));
                    });
                });
        // 整次失败：不留锁文件。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_deprecation_warning", Integer.class)).isZero();
    }

    @Test
    void historicalLockSurvivesRevokeAndReplacementWithdrawal() {
        register("app", 1, "lib", 1, 1);
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.deprecationWarnings()).hasSize(1);

        // 撤销窗口并撤回替代版本：历史锁的告警快照不得改写。
        service.revokeDeprecation(requestId(), "lib", 1);
        service.withdrawArtifact(requestId(), "lib", 2);

        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.deprecationWarnings()).isEqualTo(lock.deprecationWarnings());
        assertThat(historical.entries()).isEqualTo(lock.entries());
        assertThat(historical.createdAt()).isEqualTo(lock.createdAt());
    }

    // ------------------------------------------------------------------
    // 重解析
    // ------------------------------------------------------------------

    @Test
    void reResolveAdjudicatesAtReResolveTime() {
        register("app", 1, "lib", 1, 1);
        register("lib", 1);
        register("lib", 2);
        LockFileResponse original = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(original.deprecationWarnings()).isEmpty();

        // 登记窗口后、生效前重解析：产生告警，生成新锁文件，历史锁不变。
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));
        LockFileResponse reResolved = service.reResolveLock(requestId(), original.id());
        assertThat(reResolved.id()).isNotEqualTo(original.id());
        assertThat(reResolved.deprecationWarnings()).hasSize(1);
        assertThat(service.getLock(original.id()).deprecationWarnings()).isEmpty();

        // 生效后重解析：整次 422。
        clock.advance(Duration.ofSeconds(3600));
        assertThatThrownBy(() -> service.reResolveLock(requestId(), original.id()))
                .isInstanceOfSatisfying(DeprecationViolationException.class,
                        e -> assertThat(e.getViolations()).hasSize(1));
    }

    @Test
    void reResolveMissingLockReturns404() {
        assertThatThrownBy(() -> service.reResolveLock(requestId(), 9999))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysDeprecationBatch() {
        register("lib", 1);
        register("lib", 2);
        String rid = requestId();
        DeprecationBatchRequest batch = new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2)));

        List<DeprecationWindowResponse> first = service.registerDeprecations(rid, batch);
        List<DeprecationWindowResponse> replay = service.registerDeprecations(rid, batch);

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentDeprecationParamsReturns409() {
        register("lib", 1);
        register("lib", 2);
        String rid = requestId();
        service.registerDeprecations(rid, new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));
        assertThatThrownBy(() -> service.registerDeprecations(rid,
                new DeprecationBatchRequest(List.of(
                        window("lib", 1, T0.plusSeconds(7200), 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdRevokeReplaysOriginalSnapshot() {
        register("lib", 1);
        register("lib", 2);
        service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2))));
        String rid = requestId();
        DeprecationWindowResponse first = service.revokeDeprecation(rid, "lib", 1);
        // 窗口已撤销，若重放不生效则会 404；重放必须返回首次快照。
        DeprecationWindowResponse replay = service.revokeDeprecation(rid, "lib", 1);
        assertThat(replay).isEqualTo(first);
        assertThat(replay.revoked()).isTrue();
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentLockAndDeprecationRegistrationAreSerialized() throws Exception {
        register("app", 1, "lib", 1, 1);
        register("lib", 1);
        register("lib", 2);
        long versionBefore = repositoryVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(requestId(), new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> deprecateTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.registerDeprecations(requestId(), new DeprecationBatchRequest(List.of(
                    window("lib", 1, T0.plusSeconds(3600), 2))));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, deprecateTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object deprecateOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        // 弃用登记总是成功并推进仓库版本。
        assertThat(deprecateOutcome).isInstanceOf(List.class);
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：窗口尚未登记，无告警；结果可查询。
            assertThat(lock.deprecationWarnings()).isEmpty();
            assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
        } else {
            // 登记先提交：锁的期望版本过期，409 且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
    }

    @Test
    void concurrentSameRequestIdDeprecationBatchResolvesToSingleResult() throws Exception {
        register("lib", 1);
        register("lib", 2);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        DeprecationBatchRequest batch = new DeprecationBatchRequest(List.of(
                window("lib", 1, T0.plusSeconds(3600), 2)));

        List<Future<List<DeprecationWindowResponse>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerDeprecations(rid, batch);
            }));
        }
        pool.shutdown();

        List<DeprecationWindowResponse> first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<List<DeprecationWindowResponse>> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_deprecation", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }
}
