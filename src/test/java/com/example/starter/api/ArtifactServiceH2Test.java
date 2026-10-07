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

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, 4L, Long.MAX_VALUE));

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
                        new LockRequest("app", 1, versionBefore, Long.MAX_VALUE));
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
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 0L, Long.MAX_VALUE)))
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
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 2L, Long.MAX_VALUE)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void lockOnMissingRootReturns404() {
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("ghost", 1, 0L, Long.MAX_VALUE)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void infeasibleLockReturns422AndPersistsNothing() {
        // app:1 -> lib[2,2]，但只有 lib1；不可行。
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 2, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 2L, Long.MAX_VALUE)))
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
        LockFileResponse first = service.createLock(rid,
                new LockRequest("app", 1, 2L, Long.MAX_VALUE));

        // 登记 lib2 并撤回 lib1，仓库前进。
        service.registerArtifact(requestId(), artifact("lib", 2));
        service.withdrawArtifact(requestId(), "lib", 1);

        LockFileResponse replay = service.createLock(rid,
                new LockRequest("app", 1, 2L, Long.MAX_VALUE));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.repositoryVersion()).isEqualTo(2L);
        assertThat(replay.entries()).isEqualTo(first.entries());
    }

    // ------------------------------------------------------------------
    // 体积预算
    // ------------------------------------------------------------------

    private RegisterArtifactRequest sizedArtifact(String name, int version, long sizeBytes,
                                                  DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, sizeBytes, List.of(deps));
    }

    @Test
    void registerWithSizeBytesPersistsAndDefaultsToZero() {
        ArtifactResponse sized = service.registerArtifact(requestId(),
                sizedArtifact("app", 1, 42L));
        assertThat(sized.sizeBytes()).isEqualTo(42L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM artifact WHERE name = 'app' AND version = 1",
                Long.class)).isEqualTo(42L);

        // 旧式未声明 sizeBytes 的请求按 0 兼容。
        ArtifactResponse legacy = service.registerArtifact(requestId(), artifact("lib", 1));
        assertThat(legacy.sizeBytes()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT size_bytes FROM artifact WHERE name = 'lib' AND version = 1",
                Long.class)).isZero();
    }

    @Test
    void negativeSizeBytesReturns400() {
        assertThatThrownBy(() -> service.registerArtifact(requestId(),
                sizedArtifact("app", 1, -1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM artifact", Integer.class))
                .isZero();
    }

    @Test
    void budgetLockSelectsSmallerVersionAndFreezesTotalAndEdges() {
        // 题干合成场景：root(2B) 依赖 a[1,2] 与 b[1,1]；a2=9B、a1=5B 均依赖 b[1,1]；b=3B。
        service.registerArtifact(requestId(),
                sizedArtifact("root", 1, 2L, dep("a", 1, 2), dep("b", 1, 1)));
        service.registerArtifact(requestId(), sizedArtifact("a", 2, 9L, dep("b", 1, 1)));
        service.registerArtifact(requestId(), sizedArtifact("a", 1, 5L, dep("b", 1, 1)));
        service.registerArtifact(requestId(), sizedArtifact("b", 1, 3L));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("root", 1, 4L, 10L));

        // a2 组合 2+9+3=14 超预算，回退 a1；共享 b 只计一次：2+5+3=10。
        assertThat(lock.entries()).extracting("name", "version", "sizeBytes")
                .containsExactly(tuple("a", 1, 5L), tuple("b", 1, 3L), tuple("root", 1, 2L));
        assertThat(lock.budgetBytes()).isEqualTo(10L);
        assertThat(lock.totalSizeBytes()).isEqualTo(10L);
        assertThat(lock.edges()).extracting("fromName", "toName", "minimumVersion", "maximumVersion")
                .containsExactlyInAnyOrder(
                        tuple("root", "a", 1, 2),
                        tuple("root", "b", 1, 1),
                        tuple("a", "b", 1, 1));

        // 历史查询返回同样的冻结数据。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.totalSizeBytes()).isEqualTo(10L);
        assertThat(queried.budgetBytes()).isEqualTo(10L);
        assertThat(queried.entries()).isEqualTo(lock.entries());
        assertThat(queried.edges()).isEqualTo(lock.edges());
    }

    @Test
    void overBudgetLockReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(), sizedArtifact("app", 1, 8L, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), sizedArtifact("lib", 1, 5L));

        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 2L, 10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listLocks()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_edge", Integer.class))
                .isZero();
    }

    @Test
    void zeroBudgetLocksAllZeroSizeGraph() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));

        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, 2L, 0L));
        assertThat(lock.totalSizeBytes()).isZero();
        assertThat(lock.entries()).hasSize(2);
    }

    @Test
    void negativeOrNullBudgetReturns400() {
        service.registerArtifact(requestId(), artifact("app", 1));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 1L, -1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.createLock(requestId(),
                new LockRequest("app", 1, 1L, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThat(service.listLocks()).isEmpty();
    }

    @Test
    void lockFreezesSizesAgainstLaterMetadataChanges() {
        service.registerArtifact(requestId(), sizedArtifact("app", 1, 4L, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), sizedArtifact("lib", 1, 6L));
        LockFileResponse lock = service.createLock(requestId(),
                new LockRequest("app", 1, 2L, 10L));
        assertThat(lock.totalSizeBytes()).isEqualTo(10L);

        // 后续登记更大体积的 lib2、撤回 lib1：历史锁文件不被补齐或改写。
        service.registerArtifact(requestId(), sizedArtifact("lib", 2, 999L));
        service.withdrawArtifact(requestId(), "lib", 1);

        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.totalSizeBytes()).isEqualTo(10L);
        assertThat(queried.entries()).extracting("name", "version", "sizeBytes")
                .containsExactly(tuple("app", 1, 4L), tuple("lib", 1, 6L));
        assertThat(queried.edges()).extracting("fromName", "toName")
                .containsExactly(tuple("app", "lib"));
    }

    @Test
    void sameRequestIdWithDifferentBudgetReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        String rid = requestId();
        service.createLock(rid, new LockRequest("app", 1, 1L, 100L));
        // 旧成功键改预算 → 409。
        assertThatThrownBy(() -> service.createLock(rid, new LockRequest("app", 1, 1L, 200L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listLocks()).hasSize(1);
    }

    @Test
    void sameRequestIdSameBudgetReplaysOriginalLock() {
        service.registerArtifact(requestId(), sizedArtifact("app", 1, 4L));
        String rid = requestId();
        LockFileResponse first = service.createLock(rid, new LockRequest("app", 1, 1L, 100L));
        LockFileResponse replay = service.createLock(rid, new LockRequest("app", 1, 1L, 100L));
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.totalSizeBytes()).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedOverBudgetLockDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), sizedArtifact("app", 1, 50L));
        String rid = requestId();
        assertThatThrownBy(() -> service.createLock(rid, new LockRequest("app", 1, 1L, 10L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 失败不占键：同一 requestId 提高预算后成功。
        LockFileResponse lock = service.createLock(rid, new LockRequest("app", 1, 1L, 100L));
        assertThat(lock.totalSizeBytes()).isEqualTo(50L);
    }

    @Test
    void concurrentSameLockRequestProducesSingleLockFile() throws Exception {
        service.registerArtifact(requestId(), sizedArtifact("app", 1, 4L, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), sizedArtifact("lib", 1, 6L));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        LockRequest request = new LockRequest("app", 1, 2L, 10L);

        List<Future<LockFileResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.createLock(rid, request);
            }));
        }
        pool.shutdown();

        LockFileResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        assertThat(first.totalSizeBytes()).isEqualTo(10L);
        for (Future<LockFileResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).id()).isEqualTo(first.id());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }
}
