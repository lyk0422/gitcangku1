package com.example.starter.api;

import com.example.starter.api.dto.AttestRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.ChecksumDetailResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.LockDiagnosticsResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;
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
 * 制品校验摘要见证特性的 H2 数据库测试：
 * 覆盖摘要登记、双人见证、撤销、锁定门槛的主流程、失败分支、
 * 幂等重放、事务回滚与真实并发边界。
 */
@SpringBootTest
class ChecksumAttestationH2Test {

    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_attestation");
        jdbcTemplate.update("DELETE FROM artifact_checksum");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private long repoVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    private void register(String name, int version) {
        service.registerArtifact(requestId(), new RegisterArtifactRequest(name, version, List.of()));
    }

    private ChecksumResponse checksum(String name, int version, String digest) {
        return service.registerChecksum(requestId(), "rel-lead", name, version,
                new RegisterChecksumRequest("SHA-256", digest));
    }

    private AttestationResponse attest(String operator, String name, int version, String digest) {
        return service.attest(requestId(), operator, name, version, new AttestRequest(digest));
    }

    private void attestFully(String name, int version, String digest) {
        checksum(name, version, digest);
        attest("ops-a", name, version, digest);
        attest("ops-b", name, version, digest);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void checksumRegistrationAndDualAttestationMainFlow() {
        register("app", 1);

        ChecksumResponse checksum = checksum("app", 1, DIGEST_A.toUpperCase());
        // 大写输入被规范化为小写；算法大小写不敏感。
        assertThat(checksum.algorithm()).isEqualTo("SHA-256");
        assertThat(checksum.digest()).isEqualTo(DIGEST_A);
        assertThat(checksum.registeredBy()).isEqualTo("rel-lead");
        assertThat(checksum.repositoryVersion()).isEqualTo(2L);

        AttestationResponse first = attest("ops-a", "app", 1, DIGEST_A);
        assertThat(first.activeAttestations()).isEqualTo(1);
        assertThat(first.requiredAttestations()).isEqualTo(2);
        assertThat(first.missingAttestations()).isEqualTo(1);
        assertThat(first.revoked()).isFalse();

        AttestationResponse second = attest("ops-b", "app", 1, DIGEST_A);
        assertThat(second.activeAttestations()).isEqualTo(2);
        assertThat(second.missingAttestations()).isEqualTo(0);

        // 明细查询：见证按操作者字典序，未撤销 revokedAt 为 null。
        ChecksumDetailResponse detail = service.getChecksumDetail("app", 1);
        assertThat(detail.algorithm()).isEqualTo("SHA-256");
        assertThat(detail.digest()).isEqualTo(DIGEST_A);
        assertThat(detail.attestations()).extracting("operator", "revoked")
                .containsExactly(tuple("ops-a", false), tuple("ops-b", false));
        assertThat(detail.attestations().get(0).revokedAt()).isNull();
        assertThat(detail.activeAttestations()).isEqualTo(2);
        assertThat(detail.missingAttestations()).isEqualTo(0);
    }

    @Test
    void lockSucceedsOnlyWithChecksumAndDualAttestationAndFreezesEvidence() {
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 1,
                List.of(new com.example.starter.api.dto.DependencySpec("lib", 1, 1))));
        register("lib", 1);
        attestFully("app", 1, DIGEST_A);
        attestFully("lib", 1, DIGEST_B);

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion()));

        assertThat(lock.entries()).hasSize(2);
        assertThat(lock.entries().get(0).checksumAlgorithm()).isEqualTo("SHA-256");
        assertThat(lock.entries().get(0).checksumDigest()).isEqualTo(DIGEST_A);
        assertThat(lock.entries().get(0).attesters()).containsExactly("ops-a", "ops-b");
        assertThat(lock.entries().get(0).attestationCount()).isEqualTo(2);
        assertThat(lock.entries().get(1).checksumDigest()).isEqualTo(DIGEST_B);
    }

    @Test
    void revocationDoesNotRewriteOldLockButBlocksLaterLocks() {
        register("app", 1);
        attestFully("app", 1, DIGEST_A);
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion()));

        // 撤销一名见证人：旧锁文件保持锁定时的证据。
        AttestationResponse revoked = service.revokeAttestation(
                requestId(), "sec-admin", "app", 1, "ops-b");
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(revoked.activeAttestations()).isEqualTo(1);
        assertThat(revoked.missingAttestations()).isEqualTo(1);

        LockFileResponse oldLock = service.getLock(lock.id());
        assertThat(oldLock.entries().get(0).attesters()).containsExactly("ops-a", "ops-b");
        assertThat(oldLock.entries().get(0).attestationCount()).isEqualTo(2);

        // 后续锁定因见证不足失败。
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("app:1").contains("实际 1 人")
                            .contains("要求 2 人").contains("差额 1 人");
                });

        // 撤销后同一角色可再次见证，恢复锁定资格。
        attest("ops-b", "app", 1, DIGEST_A);
        LockFileResponse newLock = service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion()));
        assertThat(newLock.entries().get(0).attesters()).containsExactly("ops-a", "ops-b");
    }

    @Test
    void diagnosticsReportPerVersionReadiness() {
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 1,
                List.of(new com.example.starter.api.dto.DependencySpec("lib", 1, 1))));
        register("lib", 1);
        checksum("app", 1, DIGEST_A);
        attest("ops-a", "app", 1, DIGEST_A);
        attest("ops-b", "app", 1, DIGEST_A);
        checksum("lib", 1, DIGEST_C);
        // lib:1 只有摘要，无人见证。

        LockDiagnosticsResponse diagnostics = service.diagnoseLock("app", 1);
        assertThat(diagnostics.resolvable()).isTrue();
        assertThat(diagnostics.ready()).isFalse();
        assertThat(diagnostics.repositoryVersion()).isEqualTo(repoVersion());
        assertThat(diagnostics.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        LockDiagnosticsResponse.Entry lib = diagnostics.entries().get(1);
        assertThat(lib.checksumRegistered()).isTrue();
        assertThat(lib.activeAttestations()).isEqualTo(0);
        assertThat(lib.requiredAttestations()).isEqualTo(2);
        assertThat(lib.missingAttestations()).isEqualTo(2);
        assertThat(lib.attesters()).isEmpty();

        // 诊断是只读的：不改变仓库版本与数据。
        assertThat(repoVersion()).isEqualTo(diagnostics.repositoryVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();

        // 仅一名见证人时仍未就绪，锁定 422。
        attest("ops-a", "lib", 1, DIGEST_C);
        LockDiagnosticsResponse partial = service.diagnoseLock("app", 1);
        assertThat(partial.ready()).isFalse();
        assertThat(partial.entries().get(1).missingAttestations()).isEqualTo(1);
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 补齐第二名见证人后诊断就绪，锁定成功。
        attest("ops-b", "lib", 1, DIGEST_C);
        LockDiagnosticsResponse ready = service.diagnoseLock("app", 1);
        assertThat(ready.ready()).isTrue();
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, repoVersion()));
        assertThat(lock.entries()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void invalidDigestFormatReturns422() {
        register("app", 1);
        assertThatThrownBy(() -> checksum("app", 1, "not-a-hex-digest"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("摘要格式非法");
                });
        // 长度不足同样非法。
        assertThatThrownBy(() -> checksum("app", 1, "abc123"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isZero();
    }

    @Test
    void unsupportedAlgorithmReturns422() {
        register("app", 1);
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "rel-lead", "app", 1,
                new RegisterChecksumRequest("MD5", DIGEST_A)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("不支持的摘要算法");
                });
    }

    @Test
    void checksumOnMissingVersionReturns404() {
        assertThatThrownBy(() -> checksum("ghost", 1, DIGEST_A))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void checksumOnWithdrawnVersionReturns409() {
        register("app", 1);
        service.withdrawArtifact(requestId(), "app", 1);
        assertThatThrownBy(() -> checksum("app", 1, DIGEST_A))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void duplicateChecksumRegistrationReturns409() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        assertThatThrownBy(() -> checksum("app", 1, DIGEST_A))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 即使摘要不同，重复登记同样冲突。
        assertThatThrownBy(() -> checksum("app", 1, DIGEST_B))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void attestWithoutChecksumReturns422() {
        register("app", 1);
        assertThatThrownBy(() -> attest("ops-a", "app", 1, DIGEST_A))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("未登记内容校验摘要");
                });
    }

    @Test
    void attestWithMismatchedDigestReturns409() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        assertThatThrownBy(() -> attest("ops-a", "app", 1, DIGEST_B))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("不一致");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isZero();
    }

    @Test
    void duplicateAttestationBySameRoleReturns409() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        attest("ops-a", "app", 1, DIGEST_A);
        assertThatThrownBy(() -> attest("ops-a", "app", 1, DIGEST_A))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("同角色重复见证");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isEqualTo(1);
    }

    @Test
    void attestOnWithdrawnVersionReturns409() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        service.withdrawArtifact(requestId(), "app", 1);
        assertThatThrownBy(() -> attest("ops-a", "app", 1, DIGEST_A))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void revokeMissingAttestationReturns404AndDoubleRevokeReturns409() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        attest("ops-a", "app", 1, DIGEST_A);

        assertThatThrownBy(() -> service.revokeAttestation(
                requestId(), "sec-admin", "app", 1, "ghost"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        service.revokeAttestation(requestId(), "sec-admin", "app", 1, "ops-a");
        assertThatThrownBy(() -> service.revokeAttestation(
                requestId(), "sec-admin", "app", 1, "ops-a"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockFailsWhenIndirectDependencyNotAttested() {
        // app:1 -> mid[1,1]；mid:1 -> leaf[1,1]；leaf:1 未见证（间接依赖）。
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 1,
                List.of(new com.example.starter.api.dto.DependencySpec("mid", 1, 1))));
        service.registerArtifact(requestId(), new RegisterArtifactRequest("mid", 1,
                List.of(new com.example.starter.api.dto.DependencySpec("leaf", 1, 1))));
        register("leaf", 1);
        attestFully("app", 1, DIGEST_A);
        attestFully("mid", 1, DIGEST_B);
        checksum("leaf", 1, DIGEST_C);
        attest("ops-a", "leaf", 1, DIGEST_C);
        // leaf 仅一人见证，未达双人门槛。

        long versionBefore = repoVersion();
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, versionBefore)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("leaf:1").contains("见证不足");
                });
        // 整次锁定失败：无半成品，仓库版本不推进。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(repoVersion()).isEqualTo(versionBefore);
    }

    @Test
    void lockFailsWhenChecksumMissingAndReasonIsStable() {
        service.registerArtifact(requestId(), new RegisterArtifactRequest("app", 1,
                List.of(new com.example.starter.api.dto.DependencySpec("lib", 1, 1))));
        register("lib", 1);
        attestFully("app", 1, DIGEST_A);
        // lib:1 未登记摘要。

        // 连续两次失败返回相同原因（按名称升序验证，结果确定）。
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.createLock(requestId(),
                    new LockRequest("app", 1, repoVersion())))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(422);
                        assertThat(e.getMessage()).contains("lib:1").contains("未登记内容校验摘要");
                    });
        }
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void checksumSameRequestIdReplaysOriginalResponse() {
        register("app", 1);
        String rid = requestId();
        ChecksumResponse first = service.registerChecksum(rid, "rel-lead", "app", 1,
                new RegisterChecksumRequest("SHA-256", DIGEST_A));
        ChecksumResponse replay = service.registerChecksum(rid, "rel-lead", "app", 1,
                new RegisterChecksumRequest("SHA-256", DIGEST_A));

        assertThat(replay.registeredAt()).isEqualTo(first.registeredAt());
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isEqualTo(1);
        // 重放不再推进仓库版本。
        assertThat(repoVersion()).isEqualTo(first.repositoryVersion());
    }

    @Test
    void checksumSameRequestIdDifferentDigestReturns409() {
        register("app", 1);
        String rid = requestId();
        service.registerChecksum(rid, "rel-lead", "app", 1,
                new RegisterChecksumRequest("SHA-256", DIGEST_A));
        assertThatThrownBy(() -> service.registerChecksum(rid, "rel-lead", "app", 1,
                new RegisterChecksumRequest("SHA-256", DIGEST_B)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void checksumSameRequestIdDifferentOperatorReturns409() {
        register("app", 1);
        String rid = requestId();
        service.registerChecksum(rid, "rel-lead", "app", 1,
                new RegisterChecksumRequest("SHA-256", DIGEST_A));
        // 操作者属于幂等指纹：同键异操作者视为异参。
        assertThatThrownBy(() -> service.registerChecksum(rid, "other-lead", "app", 1,
                new RegisterChecksumRequest("SHA-256", DIGEST_A)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void attestSameRequestIdReplaysAndFailedAttestDoesNotConsumeKey() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);

        String rid = requestId();
        AttestationResponse first = service.attest(rid, "ops-a", "app", 1,
                new AttestRequest(DIGEST_A));
        AttestationResponse replay = service.attest(rid, "ops-a", "app", 1,
                new AttestRequest(DIGEST_A));
        assertThat(replay.attestedAt()).isEqualTo(first.attestedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isEqualTo(1);

        // 失败（摘要不一致）不占键：同一 requestId 后续可用于合法请求。
        String failedRid = requestId();
        assertThatThrownBy(() -> service.attest(failedRid, "ops-b", "app", 1,
                new AttestRequest(DIGEST_B)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        AttestationResponse later = service.attest(failedRid, "ops-b", "app", 1,
                new AttestRequest(DIGEST_A));
        assertThat(later.activeAttestations()).isEqualTo(2);
    }

    @Test
    void revokeSameRequestIdReplaysOriginalResponse() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        attest("ops-a", "app", 1, DIGEST_A);

        String rid = requestId();
        AttestationResponse first = service.revokeAttestation(rid, "sec-admin", "app", 1, "ops-a");
        AttestationResponse replay = service.revokeAttestation(rid, "sec-admin", "app", 1, "ops-a");
        assertThat(replay.revokedAt()).isEqualTo(first.revokedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation WHERE revoked = 1",
                Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发边界
    // ------------------------------------------------------------------

    @Test
    void concurrentAttestationsByDistinctRolesBothSucceed() throws Exception {
        register("app", 1);
        checksum("app", 1, DIGEST_A);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<String> operators = List.of("ops-a", "ops-b");
        List<Future<AttestationResponse>> futures = new ArrayList<>();
        for (String operator : operators) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.attest(requestId(), operator, "app", 1,
                        new AttestRequest(DIGEST_A));
            }));
        }
        pool.shutdown();
        for (Future<AttestationResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).revoked()).isFalse();
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation WHERE revoked = 0",
                Integer.class)).isEqualTo(2);
        // 两次见证各推进一次仓库版本。
        assertThat(repoVersion()).isEqualTo(4L);
    }

    @Test
    void concurrentAttestationBySameRoleOnlyOneWins() throws Exception {
        register("app", 1);
        checksum("app", 1, DIGEST_A);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.attest(requestId(), "ops-a", "app", 1, new AttestRequest(DIGEST_A));
                    return 201;
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
            assertThat(status).isIn(201, 409);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentChecksumRegistrationSameRequestIdReplaysSingleResult() throws Exception {
        register("app", 1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<ChecksumResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerChecksum(rid, "rel-lead", "app", 1,
                        new RegisterChecksumRequest("SHA-256", DIGEST_A));
            }));
        }
        pool.shutdown();

        ChecksumResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ChecksumResponse> future : futures) {
            ChecksumResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.registeredAt()).isEqualTo(first.registeredAt());
            assertThat(response.repositoryVersion()).isEqualTo(first.repositoryVersion());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 数据库边界：唯一约束实际生效
    // ------------------------------------------------------------------

    @Test
    void uniqueConstraintOnAttestationOperatorIsEnforcedByDatabase() {
        register("app", 1);
        checksum("app", 1, DIGEST_A);
        attest("ops-a", "app", 1, DIGEST_A);
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_attestation (artifact_id, operator, digest, revoked, created_at) "
                        + "VALUES (?, 'ops-a', ?, 0, CURRENT_TIMESTAMP(6))",
                artifactId, DIGEST_A))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void checksumDetailForUnregisteredVersionHasNullChecksumFields() {
        register("app", 1);
        ChecksumDetailResponse detail = service.getChecksumDetail("app", 1);
        assertThat(detail.algorithm()).isNull();
        assertThat(detail.digest()).isNull();
        assertThat(detail.registeredBy()).isNull();
        assertThat(detail.registeredAt()).isNull();
        assertThat(detail.attestations()).isEmpty();
        assertThat(detail.activeAttestations()).isZero();
        assertThat(detail.missingAttestations()).isEqualTo(2);
    }

    @Test
    void checksumDetailOnMissingVersionReturns404() {
        assertThatThrownBy(() -> service.getChecksumDetail("ghost", 9))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void diagnosticsOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.diagnoseLock("ghost", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void diagnosticsOnWithdrawnRootReportsNotResolvable() {
        register("app", 1);
        service.withdrawArtifact(requestId(), "app", 1);
        LockDiagnosticsResponse diagnostics = service.diagnoseLock("app", 1);
        assertThat(diagnostics.resolvable()).isFalse();
        assertThat(diagnostics.ready()).isFalse();
        assertThat(diagnostics.entries()).isEmpty();
    }
}
