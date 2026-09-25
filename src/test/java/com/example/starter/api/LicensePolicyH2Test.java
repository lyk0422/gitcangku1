package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LicenseResponse;
import com.example.starter.api.dto.LicenseViolationResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.PolicyResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.SetPolicyRequest;
import com.example.starter.domain.LicenseViolation;
import com.example.starter.support.ApiException;
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
 * 许可证策略与锁定准入校验的 H2（MODE=MySQL）集成测试：
 * 覆盖许可证登记/继承、策略版本冲突、闭包校验 422、历史锁定稳定性、
 * 幂等重放与并发裁决。
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
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM namespace_policy_license");
        jdbcTemplate.update("DELETE FROM namespace_policy");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static RegisterArtifactRequest artifact(String name, int version,
                                                    DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static SetPolicyRequest policy(String namespace, long expectedVersion,
                                           boolean rejectUnknown, String... allowed) {
        return new SetPolicyRequest(namespace, expectedVersion, rejectUnknown, List.of(allowed));
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 许可证登记
    // ------------------------------------------------------------------

    @Test
    void licenseDefaultsToUnknownAndCanBeRegisteredAndCleared() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        // 未登记：查询快照中 license 为 NULL。
        String initial = jdbcTemplate.queryForObject(
                "SELECT license FROM artifact WHERE name = 'lib' AND version = 1", String.class);
        assertThat(initial).isNull();

        LicenseResponse set = service.setLicense(requestId(), "lib", 1, "MIT");
        assertThat(set.license()).isEqualTo("MIT");
        assertThat(set.repositoryVersion()).isEqualTo(2L);

        // 清除登记恢复 UNKNOWN。
        LicenseResponse cleared = service.setLicense(requestId(), "lib", 1, null);
        assertThat(cleared.license()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT license FROM artifact WHERE name = 'lib' AND version = 1", String.class))
                .isNull();
    }

    @Test
    void setLicenseOnMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.setLicense(requestId(), "ghost", 1, "MIT"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void setLicenseOnWithdrawnVersionReturns409() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.withdrawArtifact(requestId(), "lib", 1);
        assertThatThrownBy(() -> service.setLicense(requestId(), "lib", 1, "MIT"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 失败后仓库版本不前进。
        assertThat(repositoryVersion()).isEqualTo(2L);
    }

    @Test
    void blankLicenseIsNormalizedToUnknown() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, "MIT");
        LicenseResponse cleared = service.setLicense(requestId(), "lib", 1, "   ");
        assertThat(cleared.license()).isNull();
    }

    // ------------------------------------------------------------------
    // 策略配置与版本冲突
    // ------------------------------------------------------------------

    @Test
    void policyCreateAndUpdateAdvancesVersion() {
        PolicyResponse created = service.setPolicy(requestId(),
                policy("lib", 0, true, "MIT", "Apache-2.0"));
        assertThat(created.version()).isEqualTo(1L);
        assertThat(created.rejectUnknown()).isTrue();
        assertThat(created.allowedLicenses()).containsExactly("Apache-2.0", "MIT");

        PolicyResponse updated = service.setPolicy(requestId(),
                policy("lib", 1, false, "BSD-3"));
        assertThat(updated.version()).isEqualTo(2L);
        assertThat(updated.rejectUnknown()).isFalse();
        assertThat(updated.allowedLicenses()).containsExactly("BSD-3");

        PolicyResponse queried = service.getPolicy("lib");
        assertThat(queried.version()).isEqualTo(2L);
        assertThat(queried.allowedLicenses()).containsExactly("BSD-3");
        // H2 TIMESTAMP(6) 精度为微秒且四舍五入，比较时给 1 毫秒容差。
        assertThat(queried.updatedAt())
                .isCloseTo(updated.updatedAt(),
                        org.assertj.core.api.Assertions.within(1,
                                java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    void policyCreateWithNonZeroExpectedVersionReturns409() {
        assertThatThrownBy(() -> service.setPolicy(requestId(),
                policy("lib", 3, true, "MIT")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.getPolicy("lib")).isNull();
    }

    @Test
    void policyUpdateWithStaleExpectedVersionReturns409() {
        service.setPolicy(requestId(), policy("lib", 0, true, "MIT"));
        assertThatThrownBy(() -> service.setPolicy(requestId(),
                policy("lib", 0, false, "BSD-3")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 冲突后策略保持原样。
        PolicyResponse current = service.getPolicy("lib");
        assertThat(current.version()).isEqualTo(1L);
        assertThat(current.allowedLicenses()).containsExactly("MIT");
    }

    @Test
    void getPolicyOnMissingNamespaceReturnsNull() {
        assertThat(service.getPolicy("ghost")).isNull();
    }

    // ------------------------------------------------------------------
    // 锁定时的策略闭包校验
    // ------------------------------------------------------------------

    @Test
    void lockWithoutAnyPolicySucceedsWithUnknownLicenses() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        assertThat(lock.entries()).extracting("name", "version", "license", "policyVersion")
                .containsExactly(
                        tuple("app", 1, null, 0L),
                        tuple("lib", 1, null, 0L));
    }

    @Test
    void lockFails422WhenIndirectDependencyLicenseNotAllowed() {
        // app -> lib[1,1]；lib1 -> util[1,1]。util 登记 GPL-3.0，策略仅允许 MIT。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1, dep("util", 1, 1)));
        service.registerArtifact(requestId(), artifact("util", 1));
        service.setLicense(requestId(), "util", 1, "GPL-3.0");
        service.setPolicy(requestId(), policy("util", 0, false, "MIT"));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 5L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getCode()).isEqualTo("POLICY_VIOLATION");
                    assertThat(e.getViolations()).hasSize(1);
                    LicenseViolation v = e.getViolations().get(0);
                    assertThat(v.name()).isEqualTo("util");
                    assertThat(v.version()).isEqualTo(1);
                    assertThat(v.license()).isEqualTo("GPL-3.0");
                    assertThat(v.reason()).isEqualTo(LicenseViolation.REASON_NOT_ALLOWED);
                });
        // 不生成部分锁文件。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
    }

    @Test
    void lockFails422WhenUnknownRejectedAndReturnsSortedViolations() {
        // 两个命名空间均拒绝 UNKNOWN；lib 与 util 都未登记许可证。
        service.registerArtifact(requestId(), artifact("app", 1, dep("zlib", 1, 1), dep("alib", 1, 1)));
        service.registerArtifact(requestId(), artifact("zlib", 1));
        service.registerArtifact(requestId(), artifact("alib", 1));
        service.setPolicy(requestId(), policy("zlib", 0, true, "MIT"));
        service.setPolicy(requestId(), policy("alib", 0, true, "MIT"));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 5L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    // 稳定排序：按名称升序。
                    assertThat(e.getViolations()).extracting(LicenseViolation::name)
                            .containsExactly("alib", "zlib");
                    assertThat(e.getViolations()).allSatisfy(v -> {
                        assertThat(v.license()).isNull();
                        assertThat(v.reason()).isEqualTo(LicenseViolation.REASON_UNKNOWN_REJECTED);
                    });
                });
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void lockSucceedsWhenClosureLicensesAllAllowed() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "app", 1, "MIT");
        service.setLicense(requestId(), "lib", 1, "Apache-2.0");
        service.setPolicy(requestId(), policy("app", 0, true, "MIT"));
        service.setPolicy(requestId(), policy("lib", 0, true, "Apache-2.0"));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 6L));
        assertThat(lock.entries()).extracting("name", "license", "policyVersion")
                .containsExactly(
                        tuple("app", "MIT", 1L),
                        tuple("lib", "Apache-2.0", 1L));
    }

    @Test
    void failedPolicyLockDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.setPolicy(requestId(), policy("app", 0, true, "MIT"));
        String rid = requestId();
        assertThatThrownBy(() -> service.createLock(rid, new LockRequest("app", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 登记许可证后，同一 requestId 可成功复用（失败不占键）。
        service.setLicense(requestId(), "app", 1, "MIT");
        LockFileResponse lock = service.createLock(rid, new LockRequest("app", 1, 3L));
        assertThat(lock.entries()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 历史锁定稳定性：后续许可证/策略修改不改写已固化字段
    // ------------------------------------------------------------------

    @Test
    void historicalLockSnapshotIsStableAfterLicenseAndPolicyChanges() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, "MIT");
        service.setPolicy(requestId(), policy("lib", 0, false, "MIT"));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.entries()).extracting("name", "license", "policyVersion")
                .containsExactly(tuple("app", null, 0L), tuple("lib", "MIT", 1L));

        // 后续：lib 许可证改为 GPL-3.0，策略收紧到拒绝 UNKNOWN 且仅允许 Apache-2.0。
        service.setLicense(requestId(), "lib", 1, "GPL-3.0");
        service.setPolicy(requestId(), policy("lib", 1, true, "Apache-2.0"));

        // 历史锁文件字段不被改写。
        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.entries()).extracting("name", "license", "policyVersion")
                .containsExactly(tuple("app", null, 0L), tuple("lib", "MIT", 1L));
        assertThat(historical.repositoryVersion()).isEqualTo(4L);

        // 数据库层同样未被改写。
        String storedLicense = jdbcTemplate.queryForObject(
                "SELECT license FROM lock_file_entry WHERE lock_file_id = ? AND name = 'lib'",
                String.class, lock.id());
        assertThat(storedLicense).isEqualTo("MIT");
        Long storedPolicyVersion = jdbcTemplate.queryForObject(
                "SELECT policy_version FROM lock_file_entry WHERE lock_file_id = ? AND name = 'lib'",
                Long.class, lock.id());
        assertThat(storedPolicyVersion).isEqualTo(1L);

        // 新锁定按新策略裁决：lib 现为 GPL-3.0，不在允许集合 → 422。
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void policyChangeDoesNotRetroactivelyInvalidateExistingLocks() {
        service.registerArtifact(requestId(), artifact("app", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        // 事后配置拒绝 UNKNOWN 的策略；已有锁文件不受影响。
        service.setPolicy(requestId(), policy("app", 0, true, "MIT"));
        LockFileResponse historical = service.getLock(lock.id());
        assertThat(historical.entries()).extracting("name", "license")
                .containsExactly(tuple("app", (String) null));
    }

    // ------------------------------------------------------------------
    // 违规诊断查询
    // ------------------------------------------------------------------

    @Test
    void diagnoseReturnsViolationsWithoutPersistingLock() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setPolicy(requestId(), policy("lib", 0, true, "MIT"));

        List<LicenseViolationResponse> violations =
                service.diagnoseLock(new LockRequest("app", 1, 3L));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).name()).isEqualTo("lib");
        assertThat(violations.get(0).reason())
                .isEqualTo(LicenseViolation.REASON_UNKNOWN_REJECTED);
        // 诊断不落库、不推进仓库版本。
        assertThat(service.listLocks()).isEmpty();
        assertThat(repositoryVersion()).isEqualTo(3L);

        // 修复后诊断通过。
        service.setLicense(requestId(), "lib", 1, "MIT");
        assertThat(service.diagnoseLock(new LockRequest("app", 1, 4L))).isEmpty();
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void setLicenseReplaysWithSameRequestId() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        String rid = requestId();
        LicenseResponse first = service.setLicense(rid, "lib", 1, "MIT");
        LicenseResponse replay = service.setLicense(rid, "lib", 1, "MIT");
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        // 重放取自持久化快照（微秒精度，四舍五入），给 1 毫秒容差。
        assertThat(replay.updatedAt())
                .isCloseTo(first.updatedAt(),
                        org.assertj.core.api.Assertions.within(1,
                                java.time.temporal.ChronoUnit.MILLIS));
        // 重放不再推进仓库版本。
        assertThat(repositoryVersion()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void setLicenseSameRequestIdDifferentLicenseReturns409() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        String rid = requestId();
        service.setLicense(rid, "lib", 1, "MIT");
        assertThatThrownBy(() -> service.setLicense(rid, "lib", 1, "GPL-3.0"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void setPolicyReplaysWithSameRequestId() {
        String rid = requestId();
        PolicyResponse first = service.setPolicy(rid, policy("lib", 0, true, "MIT"));
        PolicyResponse replay = service.setPolicy(rid, policy("lib", 0, true, "MIT"));
        assertThat(replay.version()).isEqualTo(first.version());
        assertThat(replay.updatedAt())
                .isCloseTo(first.updatedAt(),
                        org.assertj.core.api.Assertions.within(1,
                                java.time.temporal.ChronoUnit.MILLIS));
        // 重放不再递增策略版本。
        assertThat(service.getPolicy("lib").version()).isEqualTo(1L);
    }

    @Test
    void setPolicySameRequestIdDifferentParamsReturns409() {
        String rid = requestId();
        service.setPolicy(rid, policy("lib", 0, true, "MIT"));
        assertThatThrownBy(() -> service.setPolicy(rid, policy("lib", 0, false, "MIT")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedSetLicenseDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.withdrawArtifact(requestId(), "lib", 1);
        String rid = requestId();
        assertThatThrownBy(() -> service.setLicense(rid, "lib", 1, "MIT"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 同一 requestId 用于另一合法请求成功。
        service.registerArtifact(requestId(), artifact("other", 1));
        LicenseResponse later = service.setLicense(rid, "other", 1, "MIT");
        assertThat(later.license()).isEqualTo("MIT");
    }

    // ------------------------------------------------------------------
    // 并发裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentPolicyUpdatesWithSameExpectedVersionExactlyOneWins() throws Exception {
        service.setPolicy(requestId(), policy("lib", 0, false, "MIT"));
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.setPolicy(requestId(),
                            policy("lib", 1, idx % 2 == 0, "L" + idx));
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
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        // 策略版本只前进一次。
        assertThat(service.getPolicy("lib").version()).isEqualTo(2L);
    }

    @Test
    void concurrentLicenseChangeAndLockAreSerializedConsistently() throws Exception {
        // app -> lib[1,1]；lib 未登记许可证；策略拒绝 UNKNOWN。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setPolicy(requestId(), policy("lib", 0, true, "MIT"));
        long versionBefore = repositoryVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRid = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRid, new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> licenseTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.setLicense(requestId(), "lib", 1, "MIT");
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, licenseTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object licenseOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(licenseOutcome).isInstanceOf(LicenseResponse.class);
        // 裁决只有两种提交顺序，都不可能产出锁文件：
        //  - 锁先裁决：版本匹配，但 lib 为 UNKNOWN 且策略拒绝 → 422；
        //  - 许可证登记先裁决：lib 变 MIT，但仓库版本已前进 → expectedVersion 过期 409。
        // 两种情况都基于一致快照，绝不出现混合状态的部分锁。
        assertThat(lockOutcome).isInstanceOf(ApiException.class);
        assertThat(((ApiException) lockOutcome).getStatus()).isIn(409, 422);
        assertThat(service.listLocks()).isEmpty();
        // 许可证登记只生效一次。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT license FROM artifact WHERE name = 'lib' AND version = 1", String.class))
                .isEqualTo("MIT");
    }

    @Test
    void concurrentSameRequestIdSetLicenseResolvesToSingleResult() throws Exception {
        service.registerArtifact(requestId(), artifact("lib", 1));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<LicenseResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.setLicense(rid, "lib", 1, "MIT");
            }));
        }
        pool.shutdown();

        LicenseResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<LicenseResponse> future : futures) {
            LicenseResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.repositoryVersion()).isEqualTo(first.repositoryVersion());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 数据库边界
    // ------------------------------------------------------------------

    @Test
    void policyLicenseUniqueConstraintIsEnforcedByDatabase() {
        service.setPolicy(requestId(), policy("lib", 0, false, "MIT"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO namespace_policy_license (namespace, license) VALUES ('lib', 'MIT')"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void withdrawnVersionKeepsLicenseButCannotBeModified() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.setLicense(requestId(), "lib", 1, "MIT");
        ArtifactResponse withdrawn = service.withdrawArtifact(requestId(), "lib", 1);
        assertThat(withdrawn.withdrawn()).isTrue();
        // 许可证记录保留。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT license FROM artifact WHERE name = 'lib' AND version = 1", String.class))
                .isEqualTo("MIT");
        // 但已撤回版本不可再修改。
        assertThatThrownBy(() -> service.setLicense(requestId(), "lib", 1, "GPL-3.0"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }
}
