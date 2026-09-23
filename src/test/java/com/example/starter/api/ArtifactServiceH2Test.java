package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
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
        jdbcTemplate.update("DELETE FROM lock_file_optional");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_platform");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps), List.of());
    }

    private RegisterArtifactRequest artifactWithPlatforms(String name, int version,
                                                          List<String> platforms,
                                                          DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps), platforms);
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max, false);
    }

    private static DependencySpec opt(String name, int min, int max) {
        return new DependencySpec(name, min, max, true);
    }

    private static final String PLATFORM = "linux/x64";

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

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, PLATFORM, 4L));

        assertThat(lock.repositoryVersion()).isEqualTo(4L);
        assertThat(lock.rootName()).isEqualTo("app");
        assertThat(lock.rootVersion()).isEqualTo(1);
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));

        // 历史查询：列表与单查一致，条目按名称升序。
        List<LockFileResponse> locks = service.listLocks();
        assertThat(locks).hasSize(1);
        assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
    }

    @Test
    void lockWithdrawConflictConcurrencyLeavesQueryableLock() throws Exception {
        // 串行准备：app:1 -> lib[1,1]，lib:1；当前仓库版本为 2。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = requestId();
        String withdrawRequestId = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRequestId,
                        new LockRequest("app", 1, PLATFORM, versionBefore));
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
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, PLATFORM, 0L)))
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
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, PLATFORM, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("ghost", 1, PLATFORM, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void infeasibleLockReturns422AndPersistsNothing() {
        // app:1 -> lib[2,2]，但只有 lib1；不可行。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 2, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("app", 1, PLATFORM, 2L)))
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
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, new LockRequest("app", 1, PLATFORM, 2L));

        // 登记 lib2 并撤回 lib1，仓库前进。
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.withdrawArtifact(requestId(), "lib", 1);

        LockFileResponse replay = service.createLock(rid, new LockRequest("app", 1, PLATFORM, 2L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(2L);
        assertThat(replay.entries()).isEqualTo(first.entries());
    }

    // ------------------------------------------------------------------
    // 目标平台
    // ------------------------------------------------------------------

    @Test
    void registerPersistsPlatformsAndResponseEchoesThem() {
        ArtifactResponse response = service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of("linux/x64", "darwin/arm64")));
        assertThat(response.platforms()).containsExactly("darwin/arm64", "linux/x64");
    }

    @Test
    void registerWithoutPlatformsMigratesToAnyAndParticipatesInPlatformLock() {
        // 无平台登记（历史行为）：在任意具体平台锁定时都应可参与。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 2L));
        assertThat(lock.targetPlatform()).isEqualTo(PLATFORM);
        assertThat(lock.entries()).hasSize(2);
    }

    @Test
    void anyPlatformArtifactParticipatesInAnyLock() {
        service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of("ANY"), dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifactWithPlatforms("lib", 1, List.of("ANY")));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 2L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
    }

    @Test
    void platformIncompatibleExactRootReturns422() {
        service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of("darwin/arm64")));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void mandatoryCandidateSupportingOnlyOtherPlatformIsFilteredAndLockReturns422() {
        // app 支持 linux/x64 且必选 lib[1,1]；lib1 只支持 darwin/arm64，必选无解 → 422，不保存。
        service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of(PLATFORM), dep("lib", 1, 1)));
        service.registerArtifact(requestId(),
                artifactWithPlatforms("lib", 1, List.of("darwin/arm64")));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void platformSpecificHighestVersionIsSkippedAndLowerCompatibleVersionChosen() {
        // lib2 仅支持 darwin/arm64，lib1 支持 linux/x64：平台过滤后必选解析必须回退到 lib1。
        service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of(PLATFORM), dep("lib", 1, 2)));
        service.registerArtifact(requestId(),
                artifactWithPlatforms("lib", 2, List.of("darwin/arm64")));
        service.registerArtifact(requestId(),
                artifactWithPlatforms("lib", 1, List.of(PLATFORM)));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 3L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
    }

    @Test
    void invalidPlatformFormatReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of("linux-x64"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void anyPlatformMixedWithConcretePlatformReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, List.of("ANY", PLATFORM))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void moreThanTenPlatformsReturn400() {
        List<String> platforms = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            platforms.add("os" + i + "/arch" + i);
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifactWithPlatforms("app", 1, platforms)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 可选依赖：加入、跳过、闭包、不影响必选
    // ------------------------------------------------------------------

    @Test
    void optionalDependencyIsIncludedWithHighestCompatibleVersionAndClosure() {
        // app 必选 core[1,1]，可选 plugin[1,2]；plugin2 必选 helper[1,1]，plugin1 无依赖。
        // 取最高 plugin2，并把必选闭包 helper 一并加入。
        service.registerArtifact(requestId(), artifact("app", 1,
                dep("core", 1, 1), opt("plugin", 1, 2)));
        service.registerArtifact(requestId(), artifact("core", 1));
        service.registerArtifact(requestId(),
                artifact("plugin", 2, dep("helper", 1, 1)));
        service.registerArtifact(requestId(), artifact("plugin", 1));
        service.registerArtifact(requestId(), artifact("helper", 1));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 5L));

        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("core", 1),
                        tuple("helper", 1), tuple("plugin", 2));
        assertThat(lock.optionalDependencies()).hasSize(1);
        assertThat(lock.optionalDependencies().get(0).status()).isEqualTo("INCLUDED");
        assertThat(lock.optionalDependencies().get(0).sourceName()).isEqualTo("app");
        assertThat(lock.optionalDependencies().get(0).dependencyName()).isEqualTo("plugin");
        assertThat(lock.optionalDependencies().get(0).selectedVersion()).isEqualTo(2);
        assertThat(lock.optionalDependencies().get(0).reason()).isNull();
    }

    @Test
    void optionalDependencyAlreadySelectedAndSatisfyingIsIncludedWithoutChangingVersion() {
        // app 必选 core[1,1] 且可选 lib[1,2]；core1 必选 lib[1,1]：必选阶段 lib1 已选，
        // 可选阶段直接记 included@1，不更换版本。
        service.registerArtifact(requestId(),
                artifact("app", 1, dep("core", 1, 1), opt("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("core", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("core", 1), tuple("lib", 1));
        assertThat(lock.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.status()).isEqualTo("INCLUDED");
                    assertThat(o.selectedVersion()).isEqualTo(1);
                });
    }

    @Test
    void optionalDependencySelectedOutOfRangeIsSkippedAndCannotReplaceSelection() {
        // app 必选 core[1,1] 且可选 lib[2,2]；core1 必选 lib[1,1]：必选固定 lib1，
        // 可选区间不满足且不能更换已选版本，记 skipped，lib 保持 1。
        service.registerArtifact(requestId(),
                artifact("app", 1, dep("core", 1, 1), opt("lib", 2, 2)));
        service.registerArtifact(requestId(), artifact("core", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("core", 1), tuple("lib", 1));
        assertThat(lock.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.status()).isEqualTo("SKIPPED");
                    assertThat(o.reason()).isEqualTo("SELECTED_VERSION_OUT_OF_RANGE");
                    assertThat(o.selectedVersion()).isNull();
                });
    }

    @Test
    void optionalDependencyWithoutAnyCompatibleVersionIsSkippedButLockSucceeds() {
        // 仅有 ghost 但区间不匹配（或不存在）：SKIPPED/NO_COMPATIBLE_CANDIDATE，锁定不失败。
        service.registerArtifact(requestId(),
                artifact("app", 1, opt("ghost", 1, 1)));
        service.registerArtifact(requestId(), artifact("ghost", 2));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 2L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1));
        assertThat(lock.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.status()).isEqualTo("SKIPPED");
                    assertThat(o.reason()).isEqualTo("NO_COMPATIBLE_CANDIDATE");
                });
        // 仅可选无解不影响锁文件保存。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.entries()).isEqualTo(lock.entries());
        assertThat(queried.optionalDependencies()).isEqualTo(lock.optionalDependencies());
        assertThat(queried.targetPlatform()).isEqualTo(lock.targetPlatform());
    }

    @Test
    void optionalClosureInfeasibleIsSkippedAndMandatorySelectionIsNotDowngraded() {
        // app 必选 lib[1,2] → 必选取 lib2；app 可选 plugin[1,1]；
        // plugin1 必选 lib[1,1]，与已选 lib2 冲突且不允许更换 → CLOSURE_INFEASIBLE，
        // lib 必须保持 2。
        service.registerArtifact(requestId(),
                artifact("app", 1, dep("lib", 1, 2), opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 2, dep("app", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(),
                artifact("plugin", 1, dep("lib", 1, 1)));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
        assertThat(lock.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.status()).isEqualTo("SKIPPED");
                    assertThat(o.reason()).isEqualTo("CLOSURE_INFEASIBLE");
                });
    }

    @Test
    void optionalResultsArePersistedInStableSourceThenDependencyOrder() {
        // zeta 可选 zopt[1,1]（不存在→skipped），app 可选 aopt[1,1]（存在→included）。
        // 存储与查询必须按 (sourceName, dependencyName) 排序：app/aopt 先于 zeta/zopt。
        service.registerArtifact(requestId(), artifact("app", 1,
                dep("zeta", 1, 1), opt("aopt", 1, 1)));
        service.registerArtifact(requestId(),
                artifact("zeta", 1, opt("zopt", 1, 1)));
        service.registerArtifact(requestId(), artifact("aopt", 1));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 3L));
        assertThat(lock.optionalDependencies()).hasSize(2);
        assertThat(lock.optionalDependencies().get(0).sourceName()).isEqualTo("app");
        assertThat(lock.optionalDependencies().get(0).dependencyName()).isEqualTo("aopt");
        assertThat(lock.optionalDependencies().get(0).status()).isEqualTo("INCLUDED");
        assertThat(lock.optionalDependencies().get(1).sourceName()).isEqualTo("zeta");
        assertThat(lock.optionalDependencies().get(1).dependencyName()).isEqualTo("zopt");
        assertThat(lock.optionalDependencies().get(1).status()).isEqualTo("SKIPPED");

        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.targetPlatform()).isEqualTo(PLATFORM);
        assertThat(queried.optionalDependencies()).isEqualTo(lock.optionalDependencies());
    }

    @Test
    void optionalIncompatibleByPlatformIsSkippedAsNoCompatibleCandidate() {
        service.registerArtifact(requestId(),
                artifact("app", 1, opt("plugin", 1, 1)));
        service.registerArtifact(requestId(),
                artifactWithPlatforms("plugin", 1, List.of("darwin/arm64")));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, PLATFORM, 2L));
        assertThat(lock.optionalDependencies()).singleElement()
                .satisfies(o -> {
                    assertThat(o.status()).isEqualTo("SKIPPED");
                    assertThat(o.reason()).isEqualTo("NO_COMPATIBLE_CANDIDATE");
                });
        assertThat(lock.entries()).extracting("name").containsExactly("app");
    }

    // ------------------------------------------------------------------
    // 新字段的幂等语义
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdDifferentPlatformsReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifactWithPlatforms("app", 1, List.of(PLATFORM)));
        assertThatThrownBy(() -> service.registerArtifact(rid,
                artifactWithPlatforms("app", 1, List.of("darwin/arm64"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdDifferentOptionalFlagReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, artifact("app", 1, dep("lib", 1, 1)));
        assertThatThrownBy(() -> service.registerArtifact(rid,
                artifact("app", 1, opt("lib", 1, 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdDifferentTargetPlatformReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        String rid = requestId();
        service.createLock(rid, new LockRequest("app", 1, PLATFORM, 1L));
        assertThatThrownBy(() -> service.createLock(rid,
                new LockRequest("app", 1, "darwin/arm64", 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockReplayReturnsSameTargetPlatformAndOptionalResults() {
        service.registerArtifact(requestId(),
                artifact("app", 1, opt("plugin", 1, 1)));
        service.registerArtifact(requestId(), artifact("plugin", 1));
        String rid = requestId();
        LockFileResponse first = service.createLock(rid,
                new LockRequest("app", 1, PLATFORM, 2L));
        LockFileResponse replay = service.createLock(rid,
                new LockRequest("app", 1, PLATFORM, 2L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.targetPlatform()).isEqualTo(PLATFORM);
        assertThat(replay.optionalDependencies()).isEqualTo(first.optionalDependencies());
    }
}
