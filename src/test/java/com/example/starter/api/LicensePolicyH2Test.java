package com.example.starter.api;

import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.DiagnoseLockRequest;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseViolation;
import com.example.starter.api.dto.LockDiagnosisResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockLicenseSnapshotResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetLicenseRequest;
import com.example.starter.support.ApiException;
import com.example.starter.support.LicenseViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 许可证策略与锁定准入校验的 H2（MODE=MySQL）数据库测试：
 * 覆盖许可证登记/继承、策略闭包校验、历史锁定稳定性、幂等与真实并发边界。
 */
@SpringBootTest
class LicensePolicyH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_license");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM license_policy_allowed");
        jdbcTemplate.update("DELETE FROM license_policy");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 许可证登记
    // ------------------------------------------------------------------

    @Test
    void setLicenseRegistersAndRevisesWhileVersionActive() {
        service.registerArtifact(requestId(), artifact("app", 1));

        LicenseResponse first = service.setLicense(requestId(), "app", 1, new SetLicenseRequest("MIT"));
        assertThat(first.license()).isEqualTo("MIT");
        assertThat(first.repositoryVersion()).isEqualTo(2L);

        LicenseResponse revised = service.setLicense(requestId(), "app", 1,
                new SetLicenseRequest("Apache-2.0"));
        assertThat(revised.license()).isEqualTo("Apache-2.0");
        assertThat(revised.repositoryVersion()).isEqualTo(3L);

        String stored = jdbcTemplate.queryForObject(
                "SELECT l.license FROM artifact_license l JOIN artifact a ON l.artifact_id = a.id "
                        + "WHERE a.name = 'app' AND a.version = 1", String.class);
        assertThat(stored).isEqualTo("Apache-2.0");
    }

    @Test
    void setLicenseOnWithdrawnVersionReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1);

        assertThatThrownBy(() -> service.setLicense(requestId(), "app", 1, new SetLicenseRequest("MIT")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_license", Integer.class)).isZero();
        // 失败不推进仓库版本。
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void setLicenseOnMissingVersionReturns404() {
        assertThatThrownBy(() -> service.setLicense(requestId(), "ghost", 1, new SetLicenseRequest("MIT")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void unregisteredLicenseIsUnknownInLockEntries() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        assertThat(lock.policyVersion()).isNull();
        assertThat(lock.entries()).extracting("name", "version", "license")
                .containsExactly(tuple("app", 1, "UNKNOWN"), tuple("lib", 1, "UNKNOWN"));
    }

    // ------------------------------------------------------------------
    // 策略配置与乐观版本
    // ------------------------------------------------------------------

    @Test
    void policyCreateQueryAndOptimisticVersionControl() {
        PolicyResponse created = service.upsertPolicy(requestId(), "app",
                new PolicyRequest(0L, List.of("MIT"), true));
        assertThat(created.version()).isEqualTo(1L);
        assertThat(created.allowedLicenses()).containsExactly("MIT");
        assertThat(created.rejectUnknown()).isTrue();
        // 策略修改推进仓库版本。
        assertThat(created.version()).isEqualTo(1L);
        assertThat(repositoryVersion()).isEqualTo(1L);

        PolicyResponse queried = service.getPolicy("app");
        assertThat(queried.version()).isEqualTo(1L);
        assertThat(queried.allowedLicenses()).containsExactly("MIT");
        assertThat(queried.rejectUnknown()).isTrue();

        // 已存在策略时 expectedVersion=0 冲突。
        assertThatThrownBy(() -> service.upsertPolicy(requestId(), "app",
                new PolicyRequest(0L, List.of("MIT"), false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 期望版本滞后冲突。
        assertThatThrownBy(() -> service.upsertPolicy(requestId(), "app",
                new PolicyRequest(9L, List.of("MIT"), false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        PolicyResponse updated = service.upsertPolicy(requestId(), "app",
                new PolicyRequest(1L, List.of("Apache-2.0", "MIT"), false));
        assertThat(updated.version()).isEqualTo(2L);
        assertThat(updated.allowedLicenses()).containsExactly("Apache-2.0", "MIT");
        assertThat(updated.rejectUnknown()).isFalse();
        assertThat(service.getPolicy("app").version()).isEqualTo(2L);
    }

    @Test
    void createPolicyWithNonZeroExpectedVersionReturns409() {
        assertThatThrownBy(() -> service.upsertPolicy(requestId(), "app",
                new PolicyRequest(3L, List.of("MIT"), false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM license_policy", Integer.class)).isZero();
    }

    @Test
    void getPolicyOnUnconfiguredNamespaceReturns404() {
        assertThatThrownBy(() -> service.getPolicy("ghost"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void duplicateAllowedLicensesReturn400() {
        assertThatThrownBy(() -> service.upsertPolicy(requestId(), "app",
                new PolicyRequest(0L, List.of("MIT", "MIT"), false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 锁定时的策略闭包校验
    // ------------------------------------------------------------------

    @Test
    void lockViolatingAllowedSetReturns422WithStableSortedViolations() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("util", 1, 1), dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("util", 1));
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("GPL-3.0"));
        service.setLicense(requestId(), "util", 1, new SetLicenseRequest("MIT"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));
        long versionBefore = repositoryVersion();

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, versionBefore)))
                .isInstanceOfSatisfying(LicenseViolationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations())
                            .containsExactly(new LicenseViolation("lib", 1, "GPL-3.0",
                                    "LICENSE_NOT_ALLOWED"));
                });
        // 整次锁定失败：不生成部分锁文件，不推进仓库版本。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
    }

    @Test
    void violationsAreSortedByNameThenVersion() {
        // 两个依赖均违规，验证返回顺序稳定按名称升序。
        service.registerArtifact(requestId(), artifact("app", 1, dep("zlib", 1, 1), dep("alib", 1, 1)));
        service.registerArtifact(requestId(), artifact("zlib", 1));
        service.registerArtifact(requestId(), artifact("alib", 1));
        service.setLicense(requestId(), "zlib", 1, new SetLicenseRequest("GPL-3.0"));
        service.setLicense(requestId(), "alib", 1, new SetLicenseRequest("BSD-3"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(LicenseViolationException.class, e ->
                        assertThat(e.getViolations()).containsExactly(
                                new LicenseViolation("alib", 1, "BSD-3", "LICENSE_NOT_ALLOWED"),
                                new LicenseViolation("zlib", 1, "GPL-3.0", "LICENSE_NOT_ALLOWED")));
    }

    @Test
    void unknownLicenseRejectedOnlyWhenPolicySaysSo() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.upsertPolicy(requestId(), "app",
                new PolicyRequest(0L, List.of("MIT"), true));

        // rejectUnknown=true：未登记的 lib 违规。
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(LicenseViolationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getViolations()).containsExactly(
                            new LicenseViolation("app", 1, "UNKNOWN", "UNKNOWN_LICENSE_REJECTED"),
                            new LicenseViolation("lib", 1, "UNKNOWN", "UNKNOWN_LICENSE_REJECTED"));
                });

        // 放宽策略为允许 UNKNOWN 后同一闭包可锁定。
        service.upsertPolicy(requestId(), "app",
                new PolicyRequest(1L, List.of("MIT"), false));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.policyVersion()).isEqualTo(2L);
        assertThat(lock.entries()).extracting("name", "license")
                .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "UNKNOWN"));
    }

    @Test
    void transitiveDependencyViolationIsNotMissed() {
        // app -> lib -> util；util 的许可证违规必须被闭包校验发现。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1, dep("util", 1, 1)));
        service.registerArtifact(requestId(), artifact("util", 1));
        service.setLicense(requestId(), "util", 1, new SetLicenseRequest("GPL-3.0"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(LicenseViolationException.class, e ->
                        assertThat(e.getViolations()).containsExactly(
                                new LicenseViolation("util", 1, "GPL-3.0", "LICENSE_NOT_ALLOWED")));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
    }

    @Test
    void lockWithoutPolicyHasNoLicenseRestriction() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("GPL-3.0"));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.policyVersion()).isNull();
        assertThat(lock.entries()).extracting("name", "license")
                .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "GPL-3.0"));
    }

    // ------------------------------------------------------------------
    // 历史锁定稳定性
    // ------------------------------------------------------------------

    @Test
    void historicalLockKeepsFrozenLicenseAndPolicyVersion() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("MIT"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.policyVersion()).isEqualTo(1L);
        assertThat(lock.entries()).extracting("name", "license")
                .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "MIT"));

        // 后续许可证修订与策略收紧不得改写历史锁文件。
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("GPL-3.0"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(1L, List.of(), false));

        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.policyVersion()).isEqualTo(1L);
        assertThat(historical.entries()).extracting("name", "license")
                .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "MIT"));

        LockLicenseSnapshotResponse snapshot = service.getLockLicenseSnapshot(lock.id());
        assertThat(snapshot.policyVersion()).isEqualTo(1L);
        assertThat(snapshot.entries()).extracting("name", "version", "license")
                .containsExactly(tuple("app", 1, "UNKNOWN"), tuple("lib", 1, "MIT"));

        // 新锁定按新策略评估：lib 现为 GPL-3.0 且允许集合为空 → 422。
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(LicenseViolationException.class, e ->
                        assertThat(e.getViolations()).containsExactly(
                                new LicenseViolation("lib", 1, "GPL-3.0", "LICENSE_NOT_ALLOWED")));
    }

    @Test
    void policyDoesNotRetroactivelyChangeExistingLock() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.policyVersion()).isNull();

        // 新策略只影响后续锁定，已有锁文件保持 policyVersion=null 与 UNKNOWN 许可证。
        service.upsertPolicy(requestId(), "app",
                new PolicyRequest(0L, List.of("MIT"), true));
        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.policyVersion()).isNull();
        assertThat(historical.entries()).extracting("name", "license")
                .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "UNKNOWN"));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void setLicenseSameRequestIdReplaysAndDifferentParamsConflict() {
        service.registerArtifact(requestId(), artifact("app", 1));
        String rid = requestId();
        LicenseResponse first = service.setLicense(rid, "app", 1, new SetLicenseRequest("MIT"));
        LicenseResponse replay = service.setLicense(rid, "app", 1, new SetLicenseRequest("MIT"));

        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(replay.updatedAt()).isEqualTo(first.updatedAt());
        // 重放不再推进仓库版本，也不重复登记。
        assertThat(repositoryVersion()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);

        assertThatThrownBy(() -> service.setLicense(rid, "app", 1, new SetLicenseRequest("BSD-3")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void upsertPolicySameRequestIdReplaysAndFailureDoesNotConsumeKey() {
        String rid = requestId();
        PolicyResponse created = service.upsertPolicy(rid, "app",
                new PolicyRequest(0L, List.of("MIT"), false));
        PolicyResponse replay = service.upsertPolicy(rid, "app",
                new PolicyRequest(0L, List.of("MIT"), false));
        assertThat(replay.version()).isEqualTo(created.version());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM license_policy", Integer.class)).isEqualTo(1);
        assertThat(repositoryVersion()).isEqualTo(1L);

        // 版本冲突的失败修改不占键：同一 requestId 可携带正确参数重试成功。
        String failedRid = requestId();
        assertThatThrownBy(() -> service.upsertPolicy(failedRid, "app",
                new PolicyRequest(9L, List.of("BSD-3"), false)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        PolicyResponse retried = service.upsertPolicy(failedRid, "app",
                new PolicyRequest(1L, List.of("BSD-3"), false));
        assertThat(retried.version()).isEqualTo(2L);
    }

    @Test
    void failedLockDueToPolicyViolationDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("GPL-3.0"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));

        String rid = requestId();
        assertThatThrownBy(() -> service.createLock(rid,
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOf(LicenseViolationException.class);

        // 422 失败不占键：修订许可证后同一 requestId 可成功锁定。
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("MIT"));
        LockFileResponse lock = service.createLock(rid,
                new LockRequest("app", 1, repositoryVersion()));
        assertThat(lock.entries()).extracting("name", "license")
                .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "MIT"));
    }

    // ------------------------------------------------------------------
    // 诊断查询
    // ------------------------------------------------------------------

    @Test
    void diagnoseReportsViolationsWithoutWriting() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("GPL-3.0"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));
        long versionBefore = repositoryVersion();

        LockDiagnosisResponse diagnosis = service.diagnoseLock(new DiagnoseLockRequest("app", 1));
        assertThat(diagnosis.feasible()).isTrue();
        assertThat(diagnosis.policyVersion()).isEqualTo(1L);
        assertThat(diagnosis.entries()).extracting("name", "version", "license")
                .containsExactly(tuple("app", 1, "UNKNOWN"), tuple("lib", 1, "GPL-3.0"));
        assertThat(diagnosis.violations()).containsExactly(
                new LicenseViolation("lib", 1, "GPL-3.0", "LICENSE_NOT_ALLOWED"));

        // 诊断是只读查询：不产生锁文件，不推进仓库版本。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(repositoryVersion()).isEqualTo(versionBefore);
    }

    @Test
    void diagnoseInfeasibleResolutionReturnsFeasibleFalse() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 2, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockDiagnosisResponse diagnosis = service.diagnoseLock(new DiagnoseLockRequest("app", 1));
        assertThat(diagnosis.feasible()).isFalse();
        assertThat(diagnosis.entries()).isEmpty();
        assertThat(diagnosis.violations()).isEmpty();
    }

    @Test
    void diagnoseOnMissingOrWithdrawnRootFails() {
        assertThatThrownBy(() -> service.diagnoseLock(new DiagnoseLockRequest("ghost", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1);
        assertThatThrownBy(() -> service.diagnoseLock(new DiagnoseLockRequest("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentLockAndLicenseChangeResolveByCommitOrder() throws Exception {
        // lib 当前 MIT 且策略允许 MIT；并发执行「锁定」与「把 lib 改为 GPL」。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("MIT"));
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));
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
        Callable<Object> licenseTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.setLicense(requestId(), "lib", 1, new SetLicenseRequest("GPL-3.0"));
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, licenseTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object licenseOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        // 许可证修订总是成功并推进仓库版本一次。
        assertThat(licenseOutcome).isInstanceOf(LicenseResponse.class);
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁定先提交：基于 MIT 状态成功，历史快照不被后续修订改写。
            assertThat(lock.entries()).extracting("name", "license")
                    .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "MIT"));
            assertThat(service.getLockLicenseSnapshot(lock.id()).entries())
                    .extracting("name", "license")
                    .containsExactly(tuple("app", "UNKNOWN"), tuple("lib", "MIT"));
        } else {
            // 许可证先提交：期望仓库版本过期，锁定 409 且无部分锁文件。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        }
    }

    @Test
    void concurrentPolicyUpdatesWithSameExpectedVersionOneWins() throws Exception {
        service.upsertPolicy(requestId(), "app", new PolicyRequest(0L, List.of("MIT"), false));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.upsertPolicy(requestId(), "app",
                            new PolicyRequest(1L, List.of("L" + idx), false));
                    return 200;
                } catch (ApiException e) {
                    return e.getStatus();
                }
            }));
        }
        pool.shutdown();

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
        // 同一期望版本并发修改：按事务提交顺序恰有一个赢家。
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(service.getPolicy("app").version()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------
    // 数据库边界：唯一约束实际生效
    // ------------------------------------------------------------------

    @Test
    void licenseUniqueConstraintPerArtifactVersionIsEnforcedByDatabase() {
        service.registerArtifact(requestId(), artifact("app", 1));
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        jdbcTemplate.update("INSERT INTO artifact_license (artifact_id, license, updated_at) "
                + "VALUES (?, 'MIT', CURRENT_TIMESTAMP(6))", artifactId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_license (artifact_id, license, updated_at) "
                        + "VALUES (?, 'BSD-3', CURRENT_TIMESTAMP(6))", artifactId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
