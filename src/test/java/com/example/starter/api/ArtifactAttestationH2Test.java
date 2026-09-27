package com.example.starter.api;

import com.example.starter.api.dto.AttestationRequest;
import com.example.starter.api.dto.AttestationResponse;
import com.example.starter.api.dto.AttestationStatusResponse;
import com.example.starter.api.dto.ChecksumResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterChecksumRequest;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * 制品校验见证的 H2（MODE=MySQL）数据库测试：
 * 摘要登记、双人见证、撤销、锁定闭包校验的主流程与失败分支，
 * 以及幂等、并发、唯一约束与只读查询边界。时间使用可控时钟。
 */
@SpringBootTest
class ArtifactAttestationH2Test {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** 可控时钟：测试可显式推进时间，验证时间字段精确值。 */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant newInstant) {
            this.instant = newInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
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
        Clock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

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
        ((MutableClock) clock).set(T0);
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    /** 由种子生成确定性的合法摘要：sha256: 加 64 位十六进制。 */
    private static String checksumOf(String seed) {
        String unit = Integer.toHexString(seed.hashCode());
        return "sha256:" + (unit.repeat(64)).substring(0, 64);
    }

    private void register(String name, int version, DependencySpec... deps) {
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest(name, version, List.of(deps)));
    }

    private void registerChecksum(String name, int version, String checksum) {
        service.registerChecksum(requestId(), name, version, new RegisterChecksumRequest(checksum));
    }

    private void attest(String name, int version, String role, String checksum) {
        service.attest(requestId(), name, version, new AttestationRequest(role, checksum));
    }

    /** 登记摘要并由 releaser/qa 两名角色完成见证。 */
    private void attestPair(String name, int version) {
        String checksum = checksumOf(name + ":" + version);
        registerChecksum(name, version, checksum);
        attest(name, version, "releaser", checksum);
        attest(name, version, "qa", checksum);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void checksumAttestationAndLockMainFlowWithControlledClock() {
        register("app", 1, new DependencySpec("lib", 1, 1));
        register("lib", 1);

        // 摘要登记时间取自可控时钟。
        ChecksumResponse checksum = service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest(checksumOf("app:1")));
        assertThat(checksum.registeredAt()).isEqualTo(T0);
        assertThat(checksum.attestationVersion()).isEqualTo(0);

        Instant t1 = Instant.parse("2026-01-01T01:00:00Z");
        ((MutableClock) clock).set(t1);
        AttestationResponse a1 = service.attest(requestId(), "app", 1,
                new AttestationRequest("releaser", checksumOf("app:1")));
        assertThat(a1.createdAt()).isEqualTo(t1);
        assertThat(a1.attestationVersion()).isEqualTo(1);
        assertThat(a1.revoked()).isFalse();

        Instant t2 = Instant.parse("2026-01-01T02:00:00Z");
        ((MutableClock) clock).set(t2);
        AttestationResponse a2 = service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", checksumOf("app:1")));
        assertThat(a2.attestationVersion()).isEqualTo(2);

        attestPair("lib", 1);

        Instant t3 = Instant.parse("2026-01-01T03:00:00Z");
        ((MutableClock) clock).set(t3);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        assertThat(lock.createdAt()).isEqualTo(t3);
        assertThat(lock.entries()).extracting("name", "version", "checksum", "attestationVersion")
                .containsExactly(
                        tuple("app", 1, checksumOf("app:1"), 2),
                        tuple("lib", 1, checksumOf("lib:1"), 2));

        // 状态明细：数量给实际值、要求值与差额。
        AttestationStatusResponse status = service.getAttestationStatus("app", 1);
        assertThat(status.requiredAttestations()).isEqualTo(2);
        assertThat(status.activeAttestations()).isEqualTo(2);
        assertThat(status.missingAttestations()).isEqualTo(0);
        assertThat(status.checksum()).isEqualTo(checksumOf("app:1"));
        assertThat(status.attestationVersion()).isEqualTo(2);
        assertThat(status.attestations()).extracting("role", "revoked")
                .containsExactly(tuple("qa", false), tuple("releaser", false));
    }

    @Test
    void checksumIsNormalizedToLowerCase() {
        register("app", 1);
        String upper = "sha256:" + "ABCD".repeat(16);
        ChecksumResponse response = service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest(upper));
        String expected = "sha256:" + "abcd".repeat(16);
        assertThat(response.checksum()).isEqualTo(expected);
        // 大写见证与规范化后的登记摘要一致，可见证。
        AttestationResponse attested = service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", upper));
        assertThat(attested.checksum()).isEqualTo(expected);
    }

    // ------------------------------------------------------------------
    // 失败分支：摘要登记
    // ------------------------------------------------------------------

    @Test
    void invalidChecksumFormatReturns422() {
        register("app", 1);
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest("md5:abc")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest("sha256:xyz")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isZero();
    }

    @Test
    void checksumOnMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "ghost", 1,
                new RegisterChecksumRequest(checksumOf("x"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void checksumOnWithdrawnVersionReturns409() {
        register("app", 1);
        service.withdrawArtifact(requestId(), "app", 1);
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest(checksumOf("app:1"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void reregisterSameChecksumIsNoopAndDifferentChecksumReturns409() {
        register("app", 1);
        ChecksumResponse first = service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest(checksumOf("app:1")));

        // 同值重复登记：幂等无操作，不改写首次登记时间。
        ChecksumResponse again = service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest(checksumOf("app:1")));
        assertThat(again.registeredAt()).isEqualTo(first.registeredAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isEqualTo(1);

        // 不同摘要 → 409。
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "app", 1,
                new RegisterChecksumRequest(checksumOf("other"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 失败分支：见证
    // ------------------------------------------------------------------

    @Test
    void attestBeforeChecksumRegistrationReturns422() {
        register("app", 1);
        assertThatThrownBy(() -> service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", checksumOf("app:1"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void attestWithMismatchedChecksumReturns422() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        assertThatThrownBy(() -> service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", checksumOf("other"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("不一致");
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isZero();
    }

    @Test
    void duplicateRoleAttestationReturns409() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));

        assertThatThrownBy(() -> service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", checksumOf("app:1"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 失败回滚：见证版本号不推进。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attestation_version FROM artifact WHERE name = 'app'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void attestOnWithdrawnVersionReturns409() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        service.withdrawArtifact(requestId(), "app", 1);
        assertThatThrownBy(() -> service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", checksumOf("app:1"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 锁定闭包校验
    // ------------------------------------------------------------------

    @Test
    void indirectDependencyUnattestedFailsWholeLockWithStableReason() {
        // app:1 -> lib[1,1]；lib:1 -> util[1,1]；util 未登记摘要。
        register("app", 1, new DependencySpec("lib", 1, 1));
        register("lib", 1, new DependencySpec("util", 1, 1));
        register("util", 1);
        attestPair("app", 1);
        attestPair("lib", 1);

        LockRequest lockRequest = new LockRequest("app", 1, 3L);
        assertThatThrownBy(() -> service.createLock(requestId(), lockRequest))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("util:1");
                    assertThat(e.getMessage()).contains("未登记内容校验摘要");
                });

        // 失败原因稳定：两次失败（不同 requestId）返回相同消息。
        final String[] messages = new String[2];
        for (int i = 0; i < 2; i++) {
            try {
                service.createLock(requestId(), lockRequest);
            } catch (ApiException e) {
                messages[i] = e.getMessage();
            }
        }
        assertThat(messages[0]).isNotNull().isEqualTo(messages[1]);

        // 整次锁定失败，不留半成品，仓库版本不推进。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(repositoryVersion()).isEqualTo(3L);
    }

    @Test
    void lockWithSingleAttestationFailsAndReportsActualRequiredMissing() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 1L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("实际=1").contains("要求=2").contains("差额=1");
                });
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void failedLockDoesNotConsumeRequestIdAndRetrySucceeds() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));

        String rid = requestId();
        LockRequest lockRequest = new LockRequest("app", 1, 1L);
        assertThatThrownBy(() -> service.createLock(rid, lockRequest))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 失败不占键：补足见证后同一 requestId 重试成功。
        attest("app", 1, "releaser", checksumOf("app:1"));
        LockFileResponse lock = service.createLock(rid, lockRequest);
        assertThat(lock.entries()).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 撤销：不改写旧锁，只影响后续锁定
    // ------------------------------------------------------------------

    @Test
    void revocationDoesNotRewriteOldLockAndBlocksSubsequentLocks() {
        register("app", 1);
        attestPair("app", 1);

        LockFileResponse oldLock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        assertThat(oldLock.entries()).extracting("name", "attestationVersion")
                .containsExactly(tuple("app", 2));

        // 撤销 qa 的见证：见证版本号推进，但旧锁固化内容不变。
        AttestationResponse revoked = service.revokeAttestation(requestId(), "app", 1, "qa");
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.attestationVersion()).isEqualTo(3);

        LockFileResponse queried = service.getLock(oldLock.id());
        assertThat(queried.entries()).isEqualTo(oldLock.entries());
        assertThat(queried.entries().get(0).attestationVersion()).isEqualTo(2);

        // 撤销只影响后续锁定：有效见证不足，新锁定 422。
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 1L)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("实际=1").contains("要求=2");
                });

        // 第三名角色补见证后，新锁定成功并固化新的见证版本号。
        attest("app", 1, "security", checksumOf("app:1"));
        LockFileResponse newLock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        assertThat(newLock.entries()).extracting("name", "attestationVersion")
                .containsExactly(tuple("app", 4));

        // 旧锁仍未被改写。
        assertThat(service.getLock(oldLock.id()).entries().get(0).attestationVersion()).isEqualTo(2);
        assertThat(service.listLocks()).hasSize(2);
    }

    @Test
    void reattestAfterRevokeReactivatesSameRoleRecord() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));
        service.revokeAttestation(requestId(), "app", 1, "qa");

        AttestationResponse reattested = service.attest(requestId(), "app", 1,
                new AttestationRequest("qa", checksumOf("app:1")));
        assertThat(reattested.revoked()).isFalse();
        assertThat(reattested.attestationVersion()).isEqualTo(3);
        // 仍是一条记录：重新激活而非新增。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isEqualTo(1);

        AttestationStatusResponse status = service.getAttestationStatus("app", 1);
        assertThat(status.activeAttestations()).isEqualTo(1);
        assertThat(status.attestations().get(0).revokedAt()).isNull();
    }

    @Test
    void revokeMissingOrAlreadyRevokedAttestationFails() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));

        assertThatThrownBy(() -> service.revokeAttestation(requestId(), "app", 1, "ghost"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        service.revokeAttestation(requestId(), "app", 1, "qa");
        assertThatThrownBy(() -> service.revokeAttestation(requestId(), "app", 1, "qa"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void checksumSameRequestIdReplaysFirstSnapshot() {
        register("app", 1);
        String rid = requestId();
        RegisterChecksumRequest request = new RegisterChecksumRequest(checksumOf("app:1"));
        ChecksumResponse first = service.registerChecksum(rid, "app", 1, request);

        ((MutableClock) clock).set(Instant.parse("2026-06-01T00:00:00Z"));
        ChecksumResponse replay = service.registerChecksum(rid, "app", 1, request);
        // 重放首个快照：时间仍为首次登记时间。
        assertThat(replay.registeredAt()).isEqualTo(first.registeredAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void attestSameRequestIdReplaysAndDifferentParamsConflict() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));

        String rid = requestId();
        AttestationResponse first = service.attest(rid, "app", 1,
                new AttestationRequest("qa", checksumOf("app:1")));
        AttestationResponse replay = service.attest(rid, "app", 1,
                new AttestationRequest("qa", checksumOf("app:1")));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isEqualTo(1);

        // 同 requestId 异参（不同角色）→ 409。
        assertThatThrownBy(() -> service.attest(rid, "app", 1,
                new AttestationRequest("releaser", checksumOf("app:1"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void revokeSameRequestIdReplaysFirstSnapshot() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));

        String rid = requestId();
        AttestationResponse first = service.revokeAttestation(rid, "app", 1, "qa");
        AttestationResponse replay = service.revokeAttestation(rid, "app", 1, "qa");
        assertThat(replay.revokedAt()).isEqualTo(first.revokedAt());
        // 重放不重复推进见证版本号。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attestation_version FROM artifact WHERE name = 'app'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void failedAttestDoesNotConsumeRequestId() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));

        String rid = requestId();
        // 摘要不一致失败（422）不占键。
        assertThatThrownBy(() -> service.attest(rid, "app", 1,
                new AttestationRequest("qa", checksumOf("other"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        AttestationResponse later = service.attest(rid, "app", 1,
                new AttestationRequest("qa", checksumOf("app:1")));
        assertThat(later.role()).isEqualTo("qa");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentSameRoleAttestationOneWinsRestConflict() throws Exception {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.attest(requestId(), "app", 1,
                            new AttestationRequest("qa", checksumOf("app:1")));
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
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attestation_version FROM artifact WHERE name = 'app'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentDistinctRoleAttestationsAllSucceed() throws Exception {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));

        String[] roles = {"r1", "r2", "r3", "r4"};
        ExecutorService pool = Executors.newFixedThreadPool(roles.length);
        CyclicBarrier barrier = new CyclicBarrier(roles.length);
        List<Callable<AttestationResponse>> tasks = new ArrayList<>();
        for (String role : roles) {
            tasks.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.attest(requestId(), "app", 1,
                        new AttestationRequest(role, checksumOf("app:1")));
            });
        }
        List<Future<AttestationResponse>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        for (Future<AttestationResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).revoked()).isFalse();
        }
        AttestationStatusResponse status = service.getAttestationStatus("app", 1);
        assertThat(status.activeAttestations()).isEqualTo(4);
        assertThat(status.attestationVersion()).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // 数据库边界与只读查询
    // ------------------------------------------------------------------

    @Test
    void uniqueConstraintOnArtifactAndRoleIsEnforcedByDatabase() {
        register("app", 1);
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app'", Long.class);
        jdbcTemplate.update(
                "INSERT INTO artifact_attestation (artifact_id, role, checksum, revoked, request_id, created_at) "
                        + "VALUES (?, 'qa', ?, 0, 'r1', CURRENT_TIMESTAMP(6))",
                artifactId, checksumOf("app:1"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_attestation (artifact_id, role, checksum, revoked, request_id, created_at) "
                        + "VALUES (?, 'qa', ?, 0, 'r2', CURRENT_TIMESTAMP(6))",
                artifactId, checksumOf("app:1")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void statusQueryIsReadOnlyAndDoesNotChangeState() {
        register("app", 1);
        registerChecksum("app", 1, checksumOf("app:1"));
        attest("app", 1, "qa", checksumOf("app:1"));

        long versionBefore = repositoryVersion();
        int attestationVersionBefore = jdbcTemplate.queryForObject(
                "SELECT attestation_version FROM artifact WHERE name = 'app'", Integer.class);
        int idempotentBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class);

        AttestationStatusResponse status = service.getAttestationStatus("app", 1);
        assertThat(status.activeAttestations()).isEqualTo(1);
        assertThat(status.missingAttestations()).isEqualTo(1);
        service.getAttestationStatus("app", 1);
        service.listLocks();

        assertThat(repositoryVersion()).isEqualTo(versionBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attestation_version FROM artifact WHERE name = 'app'", Integer.class))
                .isEqualTo(attestationVersionBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class))
                .isEqualTo(idempotentBefore);
    }

    @Test
    void attestationStatusOnMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.getAttestationStatus("ghost", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }
}
