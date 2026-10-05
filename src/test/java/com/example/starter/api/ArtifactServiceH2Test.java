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
        jdbcTemplate.update("DELETE FROM lock_file_exclusion");
        jdbcTemplate.update("DELETE FROM lock_file_edge");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency_exclusion");
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

    /** 带边级排除名称的依赖。 */
    private static DependencySpec depx(String name, int min, int max, String... exclusions) {
        return new DependencySpec(name, min, max, List.of(exclusions));
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

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));

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
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, new LockRequest("app", 1, 2L));

        // 登记 lib2 并撤回 lib1，仓库前进。
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.withdrawArtifact(requestId(), "lib", 1);

        LockFileResponse replay = service.createLock(rid, new LockRequest("app", 1, 2L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(2L);
        assertThat(replay.entries()).isEqualTo(first.entries());
    }

    // ------------------------------------------------------------------
    // 边级排除：主流程与失败分支（真实 H2）
    // ------------------------------------------------------------------

    @Test
    void pathExclusionKeepsNameRequiredByOtherPathAndWithdrawOfItBreaksLock() {
        // 合成根同时依赖 a 和 b：root->a 路径排除 c，root->b 路径仍需要 c，应保留 c。
        service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "c"), dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("c", 1));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("b", 1), tuple("c", 1), tuple("root", 1));
        // 保留边含 b->c，不含被排除的 a->c。
        assertThat(lock.edges()).extracting("fromName", "toName")
                .contains(tuple("root", "a"), tuple("root", "b"), tuple("b", "c"))
                .doesNotContain(tuple("a", "c"));
        // 排除理由落库：root->a 排除 c 实际忽略了 a->c。
        assertThat(lock.exclusions()).singleElement().satisfies(record -> {
            assertThat(record.fromName()).isEqualTo("root");
            assertThat(record.toName()).isEqualTo("a");
            assertThat(record.excludedName()).isEqualTo("c");
            assertThat(record.outcome()).isEqualTo("APPLIED");
            assertThat(record.detail()).contains("a->c");
        });
        // 历史查询返回相同的边与排除记录。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.entries()).isEqualTo(lock.entries());
        assertThat(queried.edges()).isEqualTo(lock.edges());
        assertThat(queried.exclusions()).isEqualTo(lock.exclusions());

        // 撤回唯一的 c 后，b 路径仍需要 c，锁定必须失败且无半锁文件。
        service.withdrawArtifact(requestId(), "c", 1);
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("root", 1, 5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void exclusionOfUnknownNameIsAllowedAndRecordedAsMiss() {
        service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "ghost")));
        service.registerArtifact(requestId(), artifact("a", 1));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 2L));
        assertThat(lock.entries()).extracting("name").containsExactly("a", "root");
        assertThat(lock.exclusions()).singleElement().satisfies(record -> {
            assertThat(record.excludedName()).isEqualTo("ghost");
            assertThat(record.outcome()).isEqualTo("MISS");
            assertThat(record.detail()).isEqualTo("未命中");
        });
        // 未命中记录随锁文件持久化，可经历史查询还原。
        assertThat(service.getLock(lock.id()).exclusions()).isEqualTo(lock.exclusions());
    }

    @Test
    void reorderedExclusionsAreSameParamsAndReplay() {
        // 排除集合换序同参：同 requestId 重放首次完整响应，不重复登记。
        String rid = requestId();
        ArtifactResponse first = service.registerArtifact(rid,
                artifact("root", 1, depx("a", 1, 1, "x", "y")));
        ArtifactResponse replay = service.registerArtifact(rid,
                artifact("root", 1, depx("a", 1, 1, "y", "x")));

        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(1);
        // 排除名称落库且升序。
        assertThat(jdbcTemplate.queryForList(
                "SELECT excluded_name FROM artifact_dependency_exclusion ORDER BY excluded_name ASC",
                String.class)).containsExactly("x", "y");
    }

    @Test
    void duplicateExclusionNamesReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "x", "x"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void moreThanThreeExclusionsReturn400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "w", "x", "y", "z"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void infeasibleLockWithExclusionsPersistsNoEdgesOrExclusions() {
        // b->c[5,5] 保留但仓库只有 c1：422 且不留任何锁文件痕迹。
        service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "c"), dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("c", 5, 5)));
        service.registerArtifact(requestId(), artifact("c", 1));

        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("root", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_edge", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_exclusion", Integer.class)).isZero();
    }

    @Test
    void lockReplayAndLaterRegistrationsDoNotChangeStoredInterpretation() {
        service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "c"), dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("c", 1, 2)));
        service.registerArtifact(requestId(), artifact("c", 1));
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, new LockRequest("root", 1, 4L));

        // 后续新增制品推进仓库版本，重放仍返回首次完整响应。
        service.registerArtifact(requestId(), artifact("c", 2));
        LockFileResponse replay = service.createLock(rid, new LockRequest("root", 1, 4L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(4L);
        assertThat(replay.entries()).isEqualTo(first.entries());
        assertThat(replay.edges()).isEqualTo(first.edges());
        assertThat(replay.exclusions()).isEqualTo(first.exclusions());

        // 直接查询历史锁文件，保留边与排除理由不变。
        LockFileResponse queried = service.getLock(first.id());
        assertThat(queried.entries()).isEqualTo(first.entries());
        assertThat(queried.edges()).isEqualTo(first.edges());
        assertThat(queried.exclusions()).isEqualTo(first.exclusions());
    }

    @Test
    void sameRequestIdWithDifferentOperatorReturns409() {
        // 幂等身份含操作者：同键异操作者视为异参。
        String rid = requestId();
        service.registerArtifact(rid, "alice", artifact("app", 1));
        assertThatThrownBy(() -> service.registerArtifact(rid, "bob", artifact("app", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void sameRequestIdWithSameOperatorAndParamsReplays() {
        String rid = requestId();
        ArtifactResponse first = service.registerArtifact(rid, "alice", artifact("app", 1));
        ArtifactResponse replay = service.registerArtifact(rid, "alice", artifact("app", 1));
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact", Integer.class)).isEqualTo(1);
    }

    @Test
    void separatorLikeCharactersInParamsDoNotCollideIdentity() {
        // 名称含分隔符样式字符时，结构化身份仍按字段比较：异参 409、同参重放。
        String rid = requestId();
        service.registerArtifact(rid, artifact("a|1", 1));
        assertThatThrownBy(() -> service.registerArtifact(rid, artifact("a", 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        String rid2 = requestId();
        ArtifactResponse first = service.registerArtifact(rid2, artifact("b|2", 1));
        ArtifactResponse replay = service.registerArtifact(rid2, artifact("b|2", 1));
        assertThat(replay.repositoryVersion()).isEqualTo(first.repositoryVersion());
    }

    @Test
    void concurrentLockAndWithdrawWithExclusionResolvesConsistently() throws Exception {
        // root->a 排除 c、root->b 需要 c；锁定与撤回 c 并发，结果必居其一且状态一致。
        service.registerArtifact(requestId(),
                artifact("root", 1, depx("a", 1, 1, "c"), dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("c", 1));
        long versionBefore = 4L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(requestId(), new LockRequest("root", 1, versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(requestId(), "c", 1);
        };
        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, withdrawTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionBefore + 1);
        if (lockOutcome instanceof LockFileResponse lock) {
            // 锁先提交：c 被保留进锁文件，后续撤回不改旧解释。
            assertThat(lock.entries()).extracting("name").contains("c");
            LockFileResponse queried = service.getLock(lock.id());
            assertThat(queried.entries()).isEqualTo(lock.entries());
            assertThat(queried.edges()).isEqualTo(lock.edges());
        } else {
            // 撤回先提交：期望版本过期 409，无半锁文件。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listLocks()).isEmpty();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM lock_file_edge", Integer.class)).isZero();
        }
    }
}
