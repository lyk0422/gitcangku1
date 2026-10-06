package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockEdgeResponse;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.domain.DependencyScope;
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
        jdbcTemplate.update("DELETE FROM lock_file_edge");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
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
    // 私有作用域（新锁入口）
    // ------------------------------------------------------------------

    private static DependencySpec pri(String name, int min, int max) {
        return new DependencySpec(name, min, max, DependencyScope.PRIVATE);
    }

    /** 登记合成场景：根公开依赖 X1、私有依赖 P，P 公开依赖 X2；仓库版本推进到 4。 */
    private void registerSyntheticScopedGraph() {
        service.registerArtifact(requestId(),
                artifact("root", 1, dep("x", 1, 1), pri("p", 1, 1)));
        service.registerArtifact(requestId(), artifact("p", 1, dep("x", 2, 2)));
        service.registerArtifact(requestId(), artifact("x", 1));
        service.registerArtifact(requestId(), artifact("x", 2));
    }

    @Test
    void scopedLockResolvesParentAndChildScopesIndependently() {
        registerSyntheticScopedGraph();

        LockFileResponse lock = service.createScopedLock(requestId(), new LockRequest("root", 1, 4L));

        assertThat(lock.mode()).isEqualTo("SCOPED");
        assertThat(lock.repositoryVersion()).isEqualTo(4L);
        // 父域 X1、子域 X2 同时成立；同名不同域不合并为最高版本。
        assertThat(lock.entries()).containsExactly(
                new LockEntryResponse("root", 1, List.of()),
                new LockEntryResponse("x", 1, List.of()),
                new LockEntryResponse("p", 1, List.of("root")),
                new LockEntryResponse("x", 2, List.of("root")));
        // 边按登记声明冻结（含作用域与区间）。
        assertThat(lock.edges()).containsExactly(
                new LockEdgeResponse(List.of(), "root", "p", DependencyScope.PRIVATE, 1, 1),
                new LockEdgeResponse(List.of(), "root", "x", DependencyScope.PUBLIC, 1, 1),
                new LockEdgeResponse(List.of("root"), "p", "x", DependencyScope.PUBLIC, 2, 2));
        // 历史查询与创建响应一致（createdAt 经数据库微秒精度回读，单独比对）。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.id()).isEqualTo(lock.id());
        assertThat(queried.mode()).isEqualTo("SCOPED");
        assertThat(queried.repositoryVersion()).isEqualTo(4L);
        assertThat(queried.entries()).isEqualTo(lock.entries());
        assertThat(queried.edges()).isEqualTo(lock.edges());
        assertThat(service.listLocks()).hasSize(1);
        assertThat(service.listLocks().get(0).entries()).isEqualTo(lock.entries());
    }

    @Test
    void scopedLockAllPublicVariantReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(),
                artifact("root", 1, dep("x", 1, 1), dep("p", 1, 1)));
        service.registerArtifact(requestId(), artifact("p", 1, dep("x", 2, 2)));
        service.registerArtifact(requestId(), artifact("x", 1));
        service.registerArtifact(requestId(), artifact("x", 2));

        assertThatThrownBy(() -> service.createScopedLock(requestId(), new LockRequest("root", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_edge", Integer.class)).isZero();
    }

    @Test
    void scopedLockPrivateCycleReturns422() {
        // 根私有依赖 P，P 私有依赖同版本 P：不能靠创建子域逃避环。
        service.registerArtifact(requestId(), artifact("root", 1, pri("p", 1, 1)));
        service.registerArtifact(requestId(), artifact("p", 1, pri("p", 1, 1)));

        assertThatThrownBy(() -> service.createScopedLock(requestId(), new LockRequest("root", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
    }

    @Test
    void scopedLockNestingBeyondFourLevelsReturns422() {
        service.registerArtifact(requestId(), artifact("root", 1, pri("n1", 1, 1)));
        service.registerArtifact(requestId(), artifact("n1", 1, pri("n2", 1, 1)));
        service.registerArtifact(requestId(), artifact("n2", 1, pri("n3", 1, 1)));
        service.registerArtifact(requestId(), artifact("n3", 1, pri("n4", 1, 1)));
        service.registerArtifact(requestId(), artifact("n4", 1, pri("n5", 1, 1)));
        service.registerArtifact(requestId(), artifact("n5", 1));

        assertThatThrownBy(() -> service.createScopedLock(requestId(), new LockRequest("root", 1, 6L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class)).isZero();
    }

    @Test
    void scopedLockNestingUpToFourLevelsResolves() {
        service.registerArtifact(requestId(), artifact("root", 1, pri("n1", 1, 1)));
        service.registerArtifact(requestId(), artifact("n1", 1, pri("n2", 1, 1)));
        service.registerArtifact(requestId(), artifact("n2", 1, pri("n3", 1, 1)));
        service.registerArtifact(requestId(), artifact("n3", 1, pri("n4", 1, 1)));
        service.registerArtifact(requestId(), artifact("n4", 1));

        LockFileResponse lock = service.createScopedLock(requestId(), new LockRequest("root", 1, 5L));

        assertThat(lock.entries()).contains(
                new LockEntryResponse("n4", 1, List.of("root", "n1", "n2", "n3")));
    }

    @Test
    void scopedLockIdempotentReplayAndCrossOperationConflict() {
        service.registerArtifact(requestId(), artifact("root", 1));
        String rid = requestId();

        LockFileResponse first = service.createScopedLock(rid, new LockRequest("root", 1, 1L));
        LockFileResponse replay = service.createScopedLock(rid, new LockRequest("root", 1, 1L));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);

        // 同键异参（期望版本不同）→ 409。
        assertThatThrownBy(() -> service.createScopedLock(rid, new LockRequest("root", 1, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 同键跨操作（旧扁平锁入口）→ 409。
        assertThatThrownBy(() -> service.createLock(rid, new LockRequest("root", 1, 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void scopedLockStaleRepositoryVersionReturns409WithoutSaving() {
        service.registerArtifact(requestId(), artifact("root", 1));
        assertThatThrownBy(() -> service.createScopedLock(requestId(), new LockRequest("root", 1, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void flatLockKeepsOriginalRulesAndTreatsPrivateDepsAsPublic() {
        // 旧入口保持原规则：PRIVATE 依赖按 PUBLIC 并入单一作用域。
        service.registerArtifact(requestId(), artifact("root", 1, pri("p", 1, 1)));
        service.registerArtifact(requestId(), artifact("p", 1, dep("y", 1, 1)));
        service.registerArtifact(requestId(), artifact("y", 1));

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("root", 1, 3L));

        assertThat(lock.mode()).isEqualTo("FLAT");
        assertThat(lock.entries()).containsExactly(
                new LockEntryResponse("p", 1, List.of()),
                new LockEntryResponse("root", 1, List.of()),
                new LockEntryResponse("y", 1, List.of()));
    }

    @Test
    void flatLockOnScopeDependentGraphReturns422() {
        // 合成场景改为旧扁平入口：x 区间冲突，无解。
        registerSyntheticScopedGraph();
        assertThatThrownBy(() -> service.createLock(requestId(), new LockRequest("root", 1, 4L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void registerPersistsScopeAndDefaultsToPublic() {
        ArtifactResponse response = service.registerArtifact(requestId(),
                artifact("root", 1, dep("x", 1, 2), pri("p", 1, 1)));
        assertThat(response.dependencies())
                .extracting("name", "scope")
                .containsExactly(tuple("p", DependencyScope.PRIVATE),
                        tuple("x", DependencyScope.PUBLIC));

        // 撤回响应同样携带作用域。
        ArtifactResponse withdrawn = service.withdrawArtifact(requestId(), "root", 1);
        assertThat(withdrawn.dependencies())
                .extracting("name", "scope")
                .containsExactly(tuple("p", DependencyScope.PRIVATE),
                        tuple("x", DependencyScope.PUBLIC));
    }

    @Test
    void registerAndWithdrawWithExpectedRepositoryVersion() {
        service.registerArtifact(requestId(), artifact("app", 1));

        // 期望版本不一致 → 409，且不产生任何写入。
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                new RegisterArtifactRequest("lib", 1, List.of(), 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact WHERE name = 'lib'", Integer.class)).isZero();

        // 期望版本一致 → 成功。
        ArtifactResponse registered = service.registerArtifact(requestId(),
                new RegisterArtifactRequest("lib", 1, List.of(), 1L));
        assertThat(registered.repositoryVersion()).isEqualTo(2L);

        // 撤回携带过期期望版本 → 409。
        assertThatThrownBy(() -> service.withdrawArtifact(requestId(), "anonymous", "lib", 1, 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        ArtifactResponse withdrawn = service.withdrawArtifact(requestId(), "anonymous", "lib", 1, 2L);
        assertThat(withdrawn.withdrawn()).isTrue();
        assertThat(withdrawn.repositoryVersion()).isEqualTo(3L);
    }

    @Test
    void concurrentScopedLockSameRequestIdResolvesToSingleResult() throws Exception {
        registerSyntheticScopedGraph();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<LockFileResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.createScopedLock(rid, new LockRequest("root", 1, 4L));
            }));
        }
        pool.shutdown();

        LockFileResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<LockFileResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void scopedLockPersistsScopePathAndEdgesInDatabase() {
        registerSyntheticScopedGraph();
        LockFileResponse lock = service.createScopedLock(requestId(), new LockRequest("root", 1, 4L));

        List<String> scopePaths = jdbcTemplate.queryForList(
                "SELECT scope_path FROM lock_file_entry WHERE lock_file_id = ? ORDER BY scope_path, name",
                String.class, lock.id());
        assertThat(scopePaths).containsExactly("[\"root\"]", "[\"root\"]", "[]", "[]");

        Integer edgeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_edge WHERE lock_file_id = ?", Integer.class, lock.id());
        assertThat(edgeCount).isEqualTo(3);
        String privateScope = jdbcTemplate.queryForObject(
                "SELECT scope FROM lock_file_edge WHERE lock_file_id = ? AND name = 'p'",
                String.class, lock.id());
        assertThat(privateScope).isEqualTo("PRIVATE");
        String lockMode = jdbcTemplate.queryForObject(
                "SELECT lock_mode FROM lock_file WHERE id = ?", String.class, lock.id());
        assertThat(lockMode).isEqualTo("SCOPED");
    }
}
