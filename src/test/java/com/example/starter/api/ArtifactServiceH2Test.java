package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.AttestRequest;
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
 * 基于嵌入式 H2（MODE=MySQL）的服务层数据库与业务测试：
 * 覆盖主流程、失败分支、幂等重放、事务回滚以及锁定/撤回/幂等的真实并发边界。
 */
@SpringBootTest
class ArtifactServiceH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_entry_attestation");
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

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private static final String CHECKSUM_A =
            "sha256:" + "a".repeat(64);
    private static final String CHECKSUM_B =
            "sha256:" + "b".repeat(64);

    /** 登记摘要并由两名不同发布角色（alice、bob）完成见证，每次写各推进仓库版本 1。 */
    private void fullyAttest(String name, int version) {
        fullyAttest(name, version, CHECKSUM_A);
    }

    private void fullyAttest(String name, int version, String checksum) {
        service.registerChecksum(requestId(), name, version,
                new RegisterChecksumRequest(checksum), "alice");
        service.attest(requestId(), name, version, new AttestRequest(checksum), "alice");
        service.attest(requestId(), name, version, new AttestRequest(checksum), "bob");
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void registerIncrementsRepositoryVersionAndPersistsDependencies() {
        ArtifactResponse a1 = service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 2)));
        assertThat(a1.repositoryVersion()).isEqualTo(1);
        assertThat(a1.withdrawn()).isFalse();
        assertThat(a1.dependencies()).extracting("name", "minimumVersion", "maximumVersion")
                .containsExactly(tuple("lib", 1, 2));

        ArtifactResponse a2 = service.registerArtifact(requestId(),
                artifact("lib", 2));
        assertThat(a2.repositoryVersion()).isEqualTo(2);
    }

    @Test
    void lockProducesSortedEntriesAndPersistsRepositoryVersion() {
        // app:1 -> lib[1,2] 且 util[1,1]；lib2/lib1 均在，取高版本 lib2。
        service.registerArtifact(requestId(),
                artifact("app", 1, dep("util", 1, 1), dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("util", 1));
        // 闭包内每个版本都须摘要一致且双人见证：4 次登记 + 每个版本 3 次写 = 版本 16。
        fullyAttest("app", 1);
        fullyAttest("lib", 2);
        fullyAttest("lib", 1);
        fullyAttest("util", 1);

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 16L));

        assertThat(lock.repositoryVersion()).isEqualTo(16L);
        assertThat(lock.rootName()).isEqualTo("app");
        assertThat(lock.rootVersion()).isEqualTo(1);
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));
        // 摘要与两名见证角色在锁定时固化。
        assertThat(lock.entries()).allSatisfy(e -> {
            assertThat(e.checksum()).startsWith("sha256:");
            assertThat(e.attestations()).extracting("role")
                    .containsExactly("alice", "bob");
        });

        // 历史查询：列表与单查一致，条目按名称升序。
        List<LockFileResponse> locks = service.listLocks();
        assertThat(locks).hasSize(1);
        assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
    }

    @Test
    void lockWithdrawConflictConcurrencyLeavesQueryableLock() throws Exception {
        // 串行准备：app:1 -> lib[1,1]，lib:1；闭包版本完成双人见证，当前仓库版本为 8。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        fullyAttest("lib", 1);
        long versionBefore = 8L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = requestId();
        String withdrawRequestId = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRequestId,
                        new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(withdrawRequestId, "lib", 1);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, withdrawTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        long finalVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        assertThat(finalVersion).isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：即使 lib 后被撤回，锁文件仍可查询、内容不改写。
            assertThat(lock.entries()).extracting("name", "version")
                    .containsExactly(tuple("app", 1), tuple("lib", 1));
            LockFileResponse queried = service.getLock(lock.id());
            assertThat(queried.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(queried.entries()).isEqualTo(lock.entries());
        } else {
            // 撤回先提交：期望版本过期，锁必须 409，且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void duplicateArtifactVersionReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void withdrawMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "ghost", 9))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleWithdrawReturns409AndSecondWithdrawConsumesNoKey() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1);
        long versionAfterFirstWithdraw = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionAfterFirstWithdraw);
    }

    @Test
    void lockWithStaleExpectedRepositoryVersionReturns409WithoutSaving() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
    }

    @Test
    void lockOnWithdrawnRootReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1);
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("ghost", 1, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void infeasibleLockReturns422AndPersistsNothing() {
        // app:1 -> lib[2,2]，但只有 lib1；不可行。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 2, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        // 失败不推进仓库版本。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @Test
    void registeringTwentyFirstNameReturns422() {
        for (int i = 1; i <= 20; i++) {
            service.registerArtifact(requestId(), artifact("name" + i, 1));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("overflow", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void registeringSixthVersionOfNameReturns422() {
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 6)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void invalidDependencyRangeReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 3, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateDependencyNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 1), dep("lib", 2, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void moreThanTenDependenciesReturn400() {
        DependencySpec[] eleven = new DependencySpec[11];
        for (int i = 0; i < 11; i++) {
            eleven[i] = dep("lib" + i, 1, 1);
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 1, eleven)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() {
        String rid = requestId();
        RegisterArtifactRequest request = artifact("app", 1, dep("lib", 1, 1));
        ArtifactResponse first = service.registerArtifact(rid, request);
        ArtifactResponse replay = service.registerArtifact(rid, request);

        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'app'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);
        // 重放不再推进仓库版本。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("app", 2)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdAcrossOperationsReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1));
        assertThatThrownBy(() -> service.withdrawArtifact(rid, "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedBusinessCallDoesNotConsumeRequestId() {
        String rid = requestId();
        // 超限失败（422）不应占用 requestId。
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("app", 6)))
                .isInstanceOf(ApiException.class);

        // 同一 requestId 用于另一个合法请求应成功并留档。
        ArtifactResponse later = service.registerArtifact(rid, artifact("other", 1));
        assertThat(later.name()).isEqualTo("other");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdSamePayloadResolvesToSingleResult() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        RegisterArtifactRequest request = artifact("concurrent", 1);

        List<Future<ArtifactResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerArtifact(rid, request);
            }));
        }
        pool.shutdown();

        ArtifactResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ArtifactResponse> future : futures) {
            ArtifactResponse response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.repositoryVersion()).isEqualTo(first.repositoryVersion());
            assertThat(response.createdAt()).isEqualTo(first.createdAt());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'concurrent'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdDifferentPayloadOneWinsRestConflict() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.registerArtifact(rid, artifact("v" + idx, 1));
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
                "SELECT COUNT(1) FROM artifact", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 数据库边界：唯一约束实际生效
    // ------------------------------------------------------------------

    @Test
    void uniqueConstraintOnNameAndVersionIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO artifact (name, version, withdrawn, created_at) "
                + "VALUES ('x', 1, 0, CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact (name, version, withdrawn, created_at) "
                        + "VALUES ('x', 1, 0, CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void withdrawnArtifactVersionRemainsAndBlocksReRegistration() {
        service.registerArtifact(requestId(), artifact("app", 1));
        service.withdrawArtifact(requestId(), "app", 1);

        Integer withdrawnFlag = jdbcTemplate.queryForObject(
                "SELECT withdrawn FROM artifact WHERE name = 'app' AND version = 1",
                Integer.class);
        assertThat(withdrawnFlag).isEqualTo(1);

        // 撤回不删除：同 name+version 仍视为已存在，重新登记 409。
        assertThatThrownBy(() -> service.registerArtifact(requestId(), artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockReplayAfterUnrelatedChangesStillReturnsOriginalResolution() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        fullyAttest("lib", 1);
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, new LockRequest("app", 1, 8L));

        // 登记 lib2 并撤回 lib1，仓库前进。
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.withdrawArtifact(requestId(), "lib", 1);

        LockFileResponse replay = service.createLock(rid, new LockRequest("app", 1, 8L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(8L);
        assertThat(replay.entries()).isEqualTo(first.entries());
    }

    // ------------------------------------------------------------------
    // 内容摘要登记、双人见证、撤销：主流程
    // ------------------------------------------------------------------

    @Test
    void checksumRegistrationAdvancesVersionAndDiagnosticsBecomePositive() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        ChecksumResponse cs = service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        assertThat(cs.value()).isEqualTo(CHECKSUM_A);
        assertThat(cs.registeredBy()).isEqualTo("alice");
        assertThat(cs.repositoryVersion()).isEqualTo(2L);

        // 只读诊断随见证推进。
        AttestationStatusResponse s0 = service.getAttestationStatus("lib", 1);
        assertThat(s0.checksumRegistered()).isTrue();
        assertThat(s0.activeWitnessCount()).isZero();
        assertThat(s0.attested()).isFalse();
        assertThat(s0.reasons()).asString().contains("实际 0 个", "差额 2 个");

        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "alice");
        AttestationStatusResponse s1 = service.getAttestationStatus("lib", 1);
        assertThat(s1.activeWitnessCount()).isEqualTo(1);
        assertThat(s1.attested()).isFalse();
        assertThat(s1.reasons()).asString().contains("差额 1 个");

        AttestationResponse second = service.attest(requestId(), "lib", 1,
                new AttestRequest(CHECKSUM_A), "bob");
        assertThat(second.repositoryVersion()).isEqualTo(4L);
        AttestationStatusResponse ok = service.getAttestationStatus("lib", 1);
        assertThat(ok.attested()).isTrue();
        assertThat(ok.reasons()).isEmpty();
        assertThat(ok.attestations()).extracting("role", "active")
                .containsExactly(tuple("alice", true), tuple("bob", true));
    }

    @Test
    void lockFreezesChecksumAndWitnessVersionsIndependentOfLaterRevocation() {
        // app:1 -> lib[1,1]，闭包版本双人见证后锁定；随后撤销见证，旧锁证据不变。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        fullyAttest("lib", 1);
        long versionAtLock = 8L;

        LockFileResponse first = service.createLock(requestId(),
                new LockRequest("app", 1, versionAtLock));
        assertThat(first.entries()).hasSize(2);
        assertThat(first.entries()).allSatisfy(e -> {
            assertThat(e.checksum()).isEqualTo(CHECKSUM_A);
            assertThat(e.attestations()).extracting("role")
                    .containsExactly("alice", "bob");
            assertThat(e.attestations().get(0).attestedAt()).isNotNull();
        });

        // 撤销 bob 对 lib 的见证：诊断显示已撤销、不可再锁，但旧锁查询不变。
        service.revokeAttestation(requestId(), "lib", 1, "bob", "carol");
        LockFileResponse unchanged = service.getLock(first.id());
        assertThat(unchanged.repositoryVersion()).isEqualTo(versionAtLock);
        assertThat(unchanged.entries()).isEqualTo(first.entries());
        assertThat(service.getAttestationStatus("lib", 1).attestations())
                .extracting("role", "active", "revokedBy")
                .containsExactly(tuple("alice", true, null), tuple("bob", false, "carol"));

        // 见证不足后用同一根再次锁定必失败，且旧锁仍存在。
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, versionAtLock + 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        LockFileResponse refetched = service.getLock(first.id());
        assertThat(refetched.entries()).isEqualTo(first.entries());
        assertThat(refetched.entries().get(1).attestations()).hasSize(2);
    }

    @Test
    void lockFallsBackToAttestedLowerVersion() {
        // app:1 -> lib[1,2]；lib2 未见证，lib1 已见证，锁定回退取 lib1。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        fullyAttest("lib", 1);
        // lib2 只登记摘要、无见证，当前版本 10。
        service.registerChecksum(requestId(), "lib", 2,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 10L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
    }

    // ------------------------------------------------------------------
    // 失败分支：422/409/404/400
    // ------------------------------------------------------------------

    @Test
    void invalidChecksumFormatReturns422() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        for (String bad : List.of("sha256:" + "A".repeat(64), "sha256:" + "a".repeat(63),
                "sha1:" + "a".repeat(64), "not-a-checksum")) {
            assertThatThrownBy(() -> service.registerChecksum(requestId(), "lib", 1,
                    new RegisterChecksumRequest(bad), "alice"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(422));
        }
    }

    @Test
    void missingOperatorHeaderAtServiceLevelReturns400() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "  "))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateChecksumRegistrationReturns409WithoutAdvancingVersion() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        long after = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "bob"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 失败不推进版本、不留第二条摘要。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(after);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_checksum", Integer.class)).isEqualTo(1);
    }

    @Test
    void attestBeforeChecksumReturns422() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        assertThatThrownBy(() -> service.attest(requestId(), "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void duplicateRoleAttestationReturns409AndDatabaseEnforcesRoleUniqueness() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "alice");

        assertThatThrownBy(() -> service.attest(requestId(), "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 真实 H2 唯一约束：同一 artifact_id+role 不能两条。
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'lib' AND version = 1", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_attestation (artifact_id, role, checksum_value, state, "
                        + "attested_at, request_id) VALUES (?, 'alice', ?, 'ACTIVE', "
                        + "CURRENT_TIMESTAMP(6), 'rid-x')",
                artifactId, CHECKSUM_A))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void mismatchedAttestationChecksumReturns422() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        assertThatThrownBy(() -> service.attest(requestId(), "lib", 1,
                new AttestRequest(CHECKSUM_B), "bob"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains(CHECKSUM_A, CHECKSUM_B);
                        });
        // 失败的见证不入库。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isZero();
    }

    @Test
    void attestingWithdrawnVersionReturns409() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.withdrawArtifact(requestId(), "lib", 1);
        assertThatThrownBy(() -> service.attest(requestId(), "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void checksumOnMissingVersionReturns404() {
        assertThatThrownBy(() -> service.registerChecksum(requestId(), "ghost", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void revokingMissingAttestationReturns404() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        assertThatThrownBy(() -> service.revokeAttestation(requestId(), "lib", 1, "alice", "carol"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleRevokeReturns409AndFailureLeavesNoHalfState() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "alice");
        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "bob");
        service.revokeAttestation(requestId(), "lib", 1, "bob", "carol");
        long versionAfterRevoke = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.revokeAttestation(requestId(), "lib", 1, "bob", "carol"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 重复撤销不推进版本、不改变终态记录（仍恰好一条 REVOKED）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionAfterRevoke);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation WHERE state = 'REVOKED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void indirectDependencyNotAttestedFailsLockWithStableReasonsAndNoArtifact() {
        // app:1 -> lib[1,1]；仅 app 完成见证，间接依赖 lib 只登记摘要、零见证 → 422。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        // 2 次登记 + app 的 3 次见证写 + lib 的 1 次摘要 = 版本 6。
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 6L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            // 稳定返回原因：点名间接依赖、实际/要求/差额。
                            assertThat(e.getMessage()).contains("lib:1");
                            assertThat(e.getMessage()).contains("实际 0 个", "要求 2 个不同角色", "差额 2 个");
                        });
        // 整次锁定失败：无锁文件、无条目、无固化见证，版本不前进。
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_entry_attestation", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(6L);
    }

    @Test
    void mismatchedChecksumInsideClosureFailsLock() {
        // app:1 -> lib[1,1]；正常流程下见证摘要不一致在见证时即 422。
        // 此处直接经 JDBC 构造一条 bob 的不一致有效见证，验证锁定时闸门逐一枚验在真实 H2 上拦截。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "alice");
        Long libId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'lib' AND version = 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO artifact_attestation (artifact_id, role, checksum_value, state, "
                        + "attested_at, request_id) VALUES (?, 'bob', ?, 'ACTIVE', "
                        + "CURRENT_TIMESTAMP(6), 'direct-insert')",
                libId, CHECKSUM_B);
        // 2 次登记 + app 三次见证写 + lib 摘要/alice 见证两次写 = 版本 7；直插不推进版本。
        long currentVersion = 7L;

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, currentVersion)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("不一致", "bob");
                        });
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_entry_attestation", Integer.class)).isZero();
    }

    @Test
    void rootVersionNotAttestedFailsLockNamingRoot() {
        // 无依赖的根 app:1 本身未见证：锁定失败并稳定点名根版本，给出实际/要求/差额。
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("app:1");
                            assertThat(e.getMessage()).contains("未登记内容校验摘要", "差额 1 条");
                        });
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void revokeIdempotentReplayReturnsFirstSnapshot() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "alice");
        service.attest(requestId(), "lib", 1, new AttestRequest(CHECKSUM_A), "bob");

        String rid = requestId();
        AttestationResponse first = service.revokeAttestation(rid, "lib", 1, "bob", "carol");
        assertThat(first.repositoryVersion()).isEqualTo(5L);
        AttestationResponse replay = service.revokeAttestation(rid, "lib", 1, "bob", "carol");
        // 成功重放首次完整响应快照：时间与版本一致，不再产生写。
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation WHERE state = 'REVOKED'",
                Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void attestIdempotentReplayReturnsSnapshotAndNoDuplicateWrites() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        String rid = requestId();
        AttestationResponse first = service.attest(rid, "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice");
        AttestationResponse replay = service.attest(rid, "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice");

        assertThat(replay.actedAt()).isEqualTo(first.actedAt());
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_attestation", Integer.class)).isEqualTo(1);
        // 同 requestId 仅一条成功幂等记录（摘要登记使用另一 requestId）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentOperatorReturns409() {
        // 幂等指纹含操作者：同 requestId 不同 X-Operator 视为异参 → 409。
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        String rid = requestId();
        service.attest(rid, "lib", 1, new AttestRequest(CHECKSUM_A), "alice");
        assertThatThrownBy(() -> service.attest(rid, "lib", 1,
                new AttestRequest(CHECKSUM_A), "bob"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedAttestDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("lib", 1));
        String rid = requestId();
        // 摘要未登记，见证 422，失败不占键。
        assertThatThrownBy(() -> service.attest(rid, "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice"))
                .isInstanceOf(ApiException.class);
        // 补登记摘要后，同一 requestId 可成功。
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        AttestationResponse later = service.attest(rid, "lib", 1,
                new AttestRequest(CHECKSUM_A), "alice");
        assertThat(later.attestations()).extracting("role").containsExactly("alice");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发：行锁串行化，核对业务结果与最终数据
    // ------------------------------------------------------------------

    @Test
    void concurrentTwoRolesBothSucceedAndVersionAdvancesTwice() throws Exception {
        // 两名不同角色同时见证同一版本：均应成功（角色不同不冲突），版本 +2。
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");
        long before = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Object>> futures = pool.invokeAll(List.of(
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.attest(requestId(), "lib", 1,
                                new AttestRequest(CHECKSUM_A), "alice");
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.attest(requestId(), "lib", 1,
                                new AttestRequest(CHECKSUM_A), "bob");
                    } catch (ApiException e) {
                        return e;
                    }
                }));
        pool.shutdown();

        for (Future<Object> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isInstanceOf(AttestationResponse.class);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT role) FROM artifact_attestation WHERE state = 'ACTIVE'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(before + 2);
    }

    @Test
    void concurrentSameRoleAttestationSingleWinner() throws Exception {
        // 同一角色并发见证：唯一约束 + 行锁保证恰好一条成功，其余 409，无重复数据。
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerChecksum(requestId(), "lib", 1,
                new RegisterChecksumRequest(CHECKSUM_A), "alice");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.attest(requestId(), "lib", 1,
                            new AttestRequest(CHECKSUM_A), "alice");
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
    void concurrentRevokeAndLockPreservesOldLockOrBlocks() throws Exception {
        // 撤销见证与锁定并发：行锁串行化；若锁先提交则旧锁证据固化可查，否则锁定 422。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        fullyAttest("app", 1);
        fullyAttest("lib", 1);
        long versionBefore = 8L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Object>> futures = pool.invokeAll(List.of(
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        return service.createLock(requestId(),
                                new LockRequest("app", 1, versionBefore));
                    } catch (ApiException e) {
                        return e;
                    }
                },
                () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return service.revokeAttestation(requestId(), "lib", 1, "bob", "carol");
                }));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object revokeOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(revokeOutcome).isInstanceOf(AttestationResponse.class);
        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：固化两条见证，撤销后旧锁不变。
            assertThat(lock.entries().get(1).attestations()).hasSize(2);
            assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
        } else {
            // 撤销先提交：lib 见证不足，期望版本也已过期，锁失败，无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isIn(409, 422);
            assertThat(service.listLocks()).isEmpty();
        }
    }
}
