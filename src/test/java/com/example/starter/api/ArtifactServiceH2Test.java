package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.CapabilityExclusionRequest;
import com.example.starter.api.dto.CapabilityExclusionResponse;
import com.example.starter.api.dto.CapabilityPairView;
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
        jdbcTemplate.update("DELETE FROM lock_file_entry_capability");
        jdbcTemplate.update("DELETE FROM lock_file_exclusion");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_capability");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM capability_exclusion");
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

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void registerIncrementsRepositoryVersionAndPersistsDependencies() {
        ArtifactResponse a1 = service.registerArtifact(requestId(), "tester",
                artifact("app", 1, dep("lib", 1, 2)));
        assertThat(a1.repositoryVersion()).isEqualTo(1);
        assertThat(a1.withdrawn()).isFalse();
        assertThat(a1.dependencies()).extracting("name", "minimumVersion", "maximumVersion")
                .containsExactly(tuple("lib", 1, 2));

        ArtifactResponse a2 = service.registerArtifact(requestId(), "tester",
                artifact("lib", 2));
        assertThat(a2.repositoryVersion()).isEqualTo(2);
    }

    @Test
    void lockProducesSortedEntriesAndPersistsRepositoryVersion() {
        // app:1 -> lib[1,2] 且 util[1,1]；lib2/lib1 均在，取高版本 lib2。
        service.registerArtifact(requestId(), "tester",
                artifact("app", 1, dep("util", 1, 1), dep("lib", 1, 2)));
        service.registerArtifact(requestId(), "tester", artifact("lib", 2));
        service.registerArtifact(requestId(), "tester", artifact("lib", 1));
        service.registerArtifact(requestId(), "tester", artifact("util", 1));

        LockFileResponse lock = service.createLock(requestId(), "tester", new LockRequest("app", 1, 4L));

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
        service.registerArtifact(requestId(), "tester", artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), "tester", artifact("lib", 1));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = requestId();
        String withdrawRequestId = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRequestId, "tester",
                        new LockRequest("app", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(withdrawRequestId, "tester", "lib", 1);
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
        service.registerArtifact(requestId(), "tester", artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void withdrawMissingArtifactReturns404() {
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "tester", "ghost", 9))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void doubleWithdrawReturns409AndSecondWithdrawConsumesNoKey() {
        service.registerArtifact(requestId(), "tester", artifact("app", 1));
        service.withdrawArtifact(requestId(), "tester", "app", 1);
        long versionAfterFirstWithdraw = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "tester", "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionAfterFirstWithdraw);
    }

    @Test
    void lockWithStaleExpectedRepositoryVersionReturns409WithoutSaving() {
        service.registerArtifact(requestId(), "tester", artifact("app", 1));
        assertThatThrownBy(() -> service.createLock(requestId(), "tester", new LockRequest("app", 1, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
    }

    @Test
    void lockOnWithdrawnRootReturns409() {
        service.registerArtifact(requestId(), "tester", artifact("app", 1));
        service.withdrawArtifact(requestId(), "tester", "app", 1);
        assertThatThrownBy(() -> service.createLock(requestId(), "tester", new LockRequest("app", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.createLock(requestId(), "tester", new LockRequest("ghost", 1, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void infeasibleLockReturns422AndPersistsNothing() {
        // app:1 -> lib[2,2]，但只有 lib1；不可行。
        service.registerArtifact(requestId(), "tester", artifact("app", 1, dep("lib", 2, 2)));
        service.registerArtifact(requestId(), "tester", artifact("lib", 1));

        assertThatThrownBy(() -> service.createLock(requestId(), "tester", new LockRequest("app", 1, 2L)))
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
            service.registerArtifact(requestId(), "tester", artifact("name" + i, 1));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester", artifact("overflow", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void registeringSixthVersionOfNameReturns422() {
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), "tester", artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester", artifact("app", 6)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void invalidDependencyRangeReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester",
                artifact("app", 1, dep("lib", 3, 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateDependencyNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester",
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
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester", artifact("app", 1, eleven)))
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
        ArtifactResponse first = service.registerArtifact(rid, "tester", request);
        ArtifactResponse replay = service.registerArtifact(rid, "tester", request);

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
        service.registerArtifact(rid, "tester", artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(rid, "tester", artifact("app", 2)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdAcrossOperationsReturns409() {
        String rid = requestId();
        service.registerArtifact(rid, "tester", artifact("app", 1));
        assertThatThrownBy(() -> service.withdrawArtifact(rid, "tester", "app", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedBusinessCallDoesNotConsumeRequestId() {
        String rid = requestId();
        // 超限失败（422）不应占用 requestId。
        for (int v = 1; v <= 5; v++) {
            service.registerArtifact(requestId(), "tester", artifact("app", v));
        }
        assertThatThrownBy(() -> service.registerArtifact(rid, "tester", artifact("app", 6)))
                .isInstanceOf(ApiException.class);

        // 同一 requestId 用于另一个合法请求应成功并留档。
        ArtifactResponse later = service.registerArtifact(rid, "tester", artifact("other", 1));
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
                return service.registerArtifact(rid, "tester", request);
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
                    service.registerArtifact(rid, "tester", artifact("v" + idx, 1));
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
        service.registerArtifact(requestId(), "tester", artifact("app", 1));
        service.withdrawArtifact(requestId(), "tester", "app", 1);

        Integer withdrawnFlag = jdbcTemplate.queryForObject(
                "SELECT withdrawn FROM artifact WHERE name = 'app' AND version = 1",
                Integer.class);
        assertThat(withdrawnFlag).isEqualTo(1);

        // 撤回不删除：同 name+version 仍视为已存在，重新登记 409。
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockReplayAfterUnrelatedChangesStillReturnsOriginalResolution() {
        service.registerArtifact(requestId(), "tester", artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), "tester", artifact("lib", 1));
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, "tester", new LockRequest("app", 1, 2L));

        // 登记 lib2 并撤回 lib1，仓库前进。
        service.registerArtifact(requestId(), "tester", artifact("lib", 2));
        service.withdrawArtifact(requestId(), "tester", "lib", 1);

        LockFileResponse replay = service.createLock(rid, "tester", new LockRequest("app", 1, 2L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(2L);
        assertThat(replay.entries()).isEqualTo(first.entries());
    }

    // ------------------------------------------------------------------
    // 能力声明
    // ------------------------------------------------------------------

    @Test
    void registerWithCapabilitiesPersistsAndReturnsThem() {
        ArtifactResponse response = service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("app", 1, List.of(), List.of("green", "blue")));
        assertThat(response.capabilities()).containsExactly("blue", "green");
        assertThat(response.repositoryVersion()).isEqualTo(1L);

        List<String> stored = jdbcTemplate.queryForList(
                "SELECT capability FROM artifact_capability ORDER BY capability ASC", String.class);
        assertThat(stored).containsExactly("blue", "green");
    }

    @Test
    void moreThanThreeCapabilitiesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("app", 1, List.of(),
                        List.of("a", "b", "c", "d"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void duplicateCapabilityNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("app", 1, List.of(), List.of("red", "red"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    // ------------------------------------------------------------------
    // 互斥规则维护
    // ------------------------------------------------------------------

    @Test
    void addExclusionAdvancesVersionAndReplaysIdempotently() {
        String rid = requestId();
        CapabilityExclusionRequest pair = new CapabilityExclusionRequest("red", "green");
        CapabilityExclusionResponse first = service.addExclusion(rid, "tester", pair);
        assertThat(first.active()).isTrue();
        assertThat(first.capabilityA()).isEqualTo("green");
        assertThat(first.capabilityB()).isEqualTo("red");
        assertThat(first.repositoryVersion()).isEqualTo(1L);

        // 同键同参重放首次完整响应，不推进版本。
        CapabilityExclusionResponse replay = service.addExclusion(rid, "tester", pair);
        assertThat(replay.repositoryVersion()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM capability_exclusion", Integer.class)).isEqualTo(1);

        // 同键异参 409。
        assertThatThrownBy(() -> service.addExclusion(rid, "tester",
                new CapabilityExclusionRequest("red", "blue")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        assertThat(service.listExclusions())
                .containsExactly(new CapabilityPairView("green", "red"));
    }

    @Test
    void reverseOrderPairIsSameRuleAndReturns409() {
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));
        assertThatThrownBy(() -> service.addExclusion(requestId(), "tester",
                new CapabilityExclusionRequest("green", "red")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameCapabilityPairReturns400() {
        assertThatThrownBy(() -> service.addExclusion(requestId(), "tester",
                new CapabilityExclusionRequest("red", "red")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void eleventhExclusionPairReturns422() {
        for (int i = 0; i < 10; i++) {
            service.addExclusion(requestId(), "tester",
                    new CapabilityExclusionRequest("cap" + i, "capx" + i));
        }
        assertThatThrownBy(() -> service.addExclusion(requestId(), "tester",
                new CapabilityExclusionRequest("extra1", "extra2")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM capability_exclusion", Integer.class)).isEqualTo(10);
    }

    @Test
    void removeExclusionAdvancesVersionAndMissingReturns404() {
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));
        CapabilityExclusionResponse removed = service.removeExclusion(requestId(), "tester",
                new CapabilityExclusionRequest("green", "red"));
        assertThat(removed.active()).isFalse();
        assertThat(removed.repositoryVersion()).isEqualTo(2L);
        assertThat(service.listExclusions()).isEmpty();

        assertThatThrownBy(() -> service.removeExclusion(requestId(), "tester",
                new CapabilityExclusionRequest("red", "green")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void failedExclusionCallDoesNotConsumeRequestId() {
        String rid = requestId();
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));
        // 重复登记已存在规则失败（409），不占键。
        assertThatThrownBy(() -> service.addExclusion(rid, "tester",
                new CapabilityExclusionRequest("red", "green")))
                .isInstanceOf(ApiException.class);
        // 同一 requestId 用于合法请求应成功并留档。
        CapabilityExclusionResponse later = service.addExclusion(rid, "tester",
                new CapabilityExclusionRequest("blue", "yellow"));
        assertThat(later.active()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void operatorIsPartOfIdempotentIdentity() {
        String rid = requestId();
        service.registerArtifact(rid, "alice", artifact("app", 1));
        // 同键同参但操作者不同：身份不同，409。
        assertThatThrownBy(() -> service.registerArtifact(rid, "bob", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 同键同参同操作者：正常重放。
        ArtifactResponse replay = service.registerArtifact(rid, "alice", artifact("app", 1));
        assertThat(replay.repositoryVersion()).isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 锁定与能力互斥（真实 H2）
    // ------------------------------------------------------------------

    @Test
    void lockBacktracksToCompatibleCapabilityVersion() {
        // 合成场景：根提供 green，X2 提供 red，X1 提供 blue，red 与 green 互斥。
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("root", 1,
                        List.of(dep("x", 1, 2)), List.of("green")));
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("x", 2, List.of(), List.of("red")));
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("x", 1, List.of(), List.of("blue")));
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));

        LockFileResponse lock = service.createLock(requestId(), "tester",
                new LockRequest("root", 1, 4L));

        // 回退到 X1 并锁定 blue；条目携带冻结能力，规则快照冻结。
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("root", 1), tuple("x", 1));
        assertThat(lock.entries()).filteredOn(e -> e.name().equals("x"))
                .singleElement()
                .satisfies(e -> assertThat(e.capabilities()).containsExactly("blue"));
        assertThat(lock.entries()).filteredOn(e -> e.name().equals("root"))
                .singleElement()
                .satisfies(e -> assertThat(e.capabilities()).containsExactly("green"));
        assertThat(lock.exclusions())
                .containsExactly(new CapabilityPairView("green", "red"));

        // 数据库落库核对。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry_capability", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_exclusion", Integer.class)).isEqualTo(1);
    }

    @Test
    void newRuleDoesNotRewriteOldLockFileButAppliesToNewLocks() {
        // 先锁定 blue 组合。
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("root", 1,
                        List.of(dep("x", 1, 2)), List.of("green")));
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("x", 2, List.of(), List.of("red")));
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("x", 1, List.of(), List.of("blue")));
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));
        LockFileResponse oldLock = service.createLock(requestId(), "tester",
                new LockRequest("root", 1, 4L));

        // 新规则：blue 与 green 互斥；旧锁文件不倒改，制品不自动撤回。
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("blue", "green"));
        LockFileResponse reloaded = service.getLock(oldLock.id());
        assertThat(reloaded.entries()).isEqualTo(oldLock.entries());
        assertThat(reloaded.exclusions())
                .containsExactly(new CapabilityPairView("green", "red"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE withdrawn = 0", Integer.class)).isEqualTo(3);

        // 新锁定读取同一仓库版本（版本 5），此时全部组合冲突，422 且无半成品。
        assertThatThrownBy(() -> service.createLock(requestId(), "tester",
                new LockRequest("root", 1, 5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);
        assertThat(service.listLocks()).hasSize(1);
    }

    @Test
    void lockWithStaleVersionAfterRuleChangeReturns409() {
        service.registerArtifact(requestId(), "tester", artifact("app", 1));
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));
        // 规则调整推进了仓库版本：旧期望版本 1 不再有效。
        assertThatThrownBy(() -> service.createLock(requestId(), "tester",
                new LockRequest("app", 1, 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void lockInfeasibleWhenArtifactDeclaresBothMutuallyExclusiveCapabilities() {
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("app", 1, List.of(), List.of("red", "green")));
        service.addExclusion(requestId(), "tester", new CapabilityExclusionRequest("red", "green"));
        assertThatThrownBy(() -> service.createLock(requestId(), "tester",
                new LockRequest("app", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
    }

    @Test
    void concurrentAddSameExclusionPairExactlyOneWins() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    service.addExclusion(requestId(), "tester",
                            new CapabilityExclusionRequest("red", "green"));
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
                "SELECT COUNT(1) FROM capability_exclusion", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(1L);
    }

    @Test
    void exclusionPairUniqueConstraintIsEnforcedByDatabase() {
        jdbcTemplate.update("INSERT INTO capability_exclusion (capability_a, capability_b, created_at) "
                + "VALUES ('green', 'red', CURRENT_TIMESTAMP(6))");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO capability_exclusion (capability_a, capability_b, created_at) "
                        + "VALUES ('green', 'red', CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void artifactCapabilityUniqueConstraintIsEnforcedByDatabase() {
        service.registerArtifact(requestId(), "tester",
                new RegisterArtifactRequest("app", 1, List.of(), List.of("red")));
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_capability (artifact_id, capability) VALUES (?, 'red')",
                artifactId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}