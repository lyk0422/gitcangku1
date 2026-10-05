package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.EdgeView;
import com.example.starter.api.dto.JointLockFileResponse;
import com.example.starter.api.dto.JointLockRequest;
import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RootSpec;
import com.example.starter.api.dto.RootView;
import com.example.starter.support.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.temporal.ChronoUnit;
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
import static org.assertj.core.api.Assertions.within;

/**
 * 多根联合锁定的服务层 H2（MODE=MySQL）测试：
 * 主流程、失败分支、幂等重放与事务回滚，均验证真实数据库状态。
 */
@SpringBootTest
class JointLockServiceH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_edge");
        jdbcTemplate.update("DELETE FROM lock_file_root");
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

    private static RootSpec root(String name, int version) {
        return new RootSpec(name, version);
    }

    private static JointLockRequest joint(long expectedRepositoryVersion, RootSpec... roots) {
        return new JointLockRequest(List.of(roots), expectedRepositoryVersion);
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    @Test
    void jointLockIntersectsRootRangesAndFreezesGraph() {
        // 题干合成场景：根 a 需 x[1,3]，根 b 需 x[1,2]，x3、x2 均在，联合锁应选 x2。
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 3)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("x", 3));
        service.registerArtifact(requestId(), artifact("x", 2));

        JointLockFileResponse lock = service.createJointLock(requestId(),
                joint(4L, root("a", 1), root("b", 1)));

        assertThat(lock.repositoryVersion()).isEqualTo(4L);
        assertThat(lock.roots()).containsExactly(new RootView("a", 1), new RootView("b", 1));
        assertThat(lock.entries()).containsExactly(
                new LockEntryResponse("a", 1),
                new LockEntryResponse("b", 1),
                new LockEntryResponse("x", 2));
        assertThat(lock.edges()).containsExactly(
                new EdgeView("a", "x", 1, 3),
                new EdgeView("b", "x", 1, 2));

        // 联合锁查询：列表与单查一致，按名称排序。
        List<JointLockFileResponse> all = service.listJointLocks();
        assertThat(all).hasSize(1);
        assertJointLockEquals(all.get(0), lock);
        assertJointLockEquals(service.getJointLock(lock.id()), lock);
        // 联合锁不出现在单根锁查询中。
        assertThat(service.listLocks()).isEmpty();
        assertThatThrownBy(() -> service.getLock(lock.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));

        // 数据库实际冻结：根、条目、边各自行数正确。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_root WHERE lock_file_id = ?",
                Integer.class, lock.id())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_edge WHERE lock_file_id = ?",
                Integer.class, lock.id())).isEqualTo(2);
    }

    @Test
    void jointLockDoesNotMergeSingleRootHighestVersions() {
        // 单独锁 a 会取 x3；联合锁必须汇合 b 的约束取 x2，不能分别锁定再合并最高版本。
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 3)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("x", 3));
        service.registerArtifact(requestId(), artifact("x", 2));

        var single = service.createLock(requestId(),
                new com.example.starter.api.dto.LockRequest("a", 1, 4L));
        assertThat(single.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("x", 3));

        JointLockFileResponse joint = service.createJointLock(requestId(),
                joint(4L, root("a", 1), root("b", 1)));
        assertThat(joint.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("b", 1), tuple("x", 2));
    }

    @Test
    void jointLockSurvivesLaterWithdrawalUnchanged() {
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 3)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("x", 3));
        service.registerArtifact(requestId(), artifact("x", 2));
        JointLockFileResponse lock = service.createJointLock(requestId(),
                joint(4L, root("a", 1), root("b", 1)));

        // 后续撤回不改写已冻结的锁文件。
        service.withdrawArtifact(requestId(), "x", 2);
        JointLockFileResponse queried = service.getJointLock(lock.id());
        assertJointLockEquals(queried, lock);
        assertThat(queried.entries()).containsExactly(
                new LockEntryResponse("a", 1),
                new LockEntryResponse("b", 1),
                new LockEntryResponse("x", 2));
    }

    @Test
    void jointLockWithRootDependingOnAnotherRoot() {
        // 根 a:1 依赖 b[1,1]，b:1 也是根；必须满足 a 对 b 的区间。
        service.registerArtifact(requestId(), artifact("a", 1, dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1));

        JointLockFileResponse lock = service.createJointLock(requestId(),
                joint(2L, root("a", 1), root("b", 1)));
        assertThat(lock.entries()).containsExactly(
                new LockEntryResponse("a", 1), new LockEntryResponse("b", 1));
        assertThat(lock.edges()).containsExactly(new EdgeView("a", "b", 1, 1));
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void missingRootReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(), artifact("a", 1));
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(1L, root("a", 1), root("ghost", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertNoLockRows();
    }

    @Test
    void withdrawnRootReturns422AndPersistsNothing() {
        service.registerArtifact(requestId(), artifact("a", 1));
        service.registerArtifact(requestId(), artifact("b", 1));
        service.withdrawArtifact(requestId(), "b", 1);
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(3L, root("a", 1), root("b", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertNoLockRows();
    }

    @Test
    void withdrawnSharedCandidateWithoutFallbackFailsWholeOrder() {
        // 题干合成场景：撤回 x2 且无 x1 时整单失败，不保存任何锁条目。
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 3)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("x", 3));
        service.registerArtifact(requestId(), artifact("x", 2));
        service.withdrawArtifact(requestId(), "x", 2);

        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(5L, root("a", 1), root("b", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertNoLockRows();
        // 失败不推进仓库版本。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(5L);
    }

    @Test
    void emptyRangeIntersectionAcrossRootsReturns422() {
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 3, 4)));
        service.registerArtifact(requestId(), artifact("x", 4));
        service.registerArtifact(requestId(), artifact("x", 1));

        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(4L, root("a", 1), root("b", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertNoLockRows();
    }

    @Test
    void duplicateRootNameReturns400() {
        // 同名不同根版本。
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(0L, root("a", 1), root("a", 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        // 完全重复的根。
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(0L, root("a", 1), root("a", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void rootCountOutOfRangeReturns400() {
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(0L, root("a", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(0L, root("a", 1), root("b", 1), root("c", 1), root("d", 1), root("e", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void staleExpectedRepositoryVersionReturns409() {
        service.registerArtifact(requestId(), artifact("a", 1));
        service.registerArtifact(requestId(), artifact("b", 1));
        assertThatThrownBy(() -> service.createJointLock(requestId(),
                joint(1L, root("a", 1), root("b", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertNoLockRows();
    }

    private void assertNoLockRows() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_entry", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_root", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file_edge", Integer.class))
                .isZero();
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void reorderedRootsWithSameRequestIdReplaySameLock() {
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 3)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("x", 2));

        String rid = requestId();
        JointLockFileResponse first = service.createJointLock(rid,
                joint(3L, root("a", 1), root("b", 1)));
        // 根集合换序同参：重放首次完整响应。
        JointLockFileResponse replay = service.createJointLock(rid,
                joint(3L, root("b", 1), root("a", 1)));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentRootsReturns409() {
        service.registerArtifact(requestId(), artifact("a", 1));
        service.registerArtifact(requestId(), artifact("b", 1));
        service.registerArtifact(requestId(), artifact("c", 1));

        String rid = requestId();
        service.createJointLock(rid, joint(3L, root("a", 1), root("b", 1)));
        assertThatThrownBy(() -> service.createJointLock(rid,
                joint(3L, root("a", 1), root("c", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 异参冲突不产生新锁文件。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void sameRequestIdAcrossLockOperationsReturns409() {
        service.registerArtifact(requestId(), artifact("a", 1));
        service.registerArtifact(requestId(), artifact("b", 1));

        String rid = requestId();
        service.createJointLock(rid, joint(2L, root("a", 1), root("b", 1)));
        assertThatThrownBy(() -> service.createLock(rid,
                new com.example.starter.api.dto.LockRequest("a", 1, 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedJointLockDoesNotConsumeRequestId() {
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 2, 2)));
        service.registerArtifact(requestId(), artifact("b", 1));
        service.registerArtifact(requestId(), artifact("x", 1));

        String rid = requestId();
        // 不可行（a 需 x[2,2]，只有 x1）→ 422，失败不占键。
        assertThatThrownBy(() -> service.createJointLock(rid,
                joint(3L, root("a", 1), root("b", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));

        // 同一 requestId 用于合法请求应成功并留档。
        JointLockFileResponse later = service.createJointLock(rid,
                joint(3L, root("b", 1), root("x", 1)));
        assertThat(later.entries()).containsExactly(
                new LockEntryResponse("b", 1), new LockEntryResponse("x", 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发
    // ------------------------------------------------------------------

    @Test
    void concurrentJointLockAndWithdrawSerializeOnRepositoryVersion() throws Exception {
        // a:1 -> x[1,1]，b:1 -> x[1,1]，x:1；当前仓库版本为 3。
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 1)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 1)));
        service.registerArtifact(requestId(), artifact("x", 1));
        long versionBefore = 3L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRequestId = requestId();
        String withdrawRequestId = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createJointLock(lockRequestId,
                        joint(versionBefore, root("a", 1), root("b", 1)));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(withdrawRequestId, "x", 1);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, withdrawTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        long finalVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        assertThat(finalVersion).isEqualTo(versionBefore + 1);

        if (lockOutcome instanceof JointLockFileResponse lock) {
            // 锁先提交：即使 x 后被撤回，联合锁文件仍可查询、内容不改写。
            assertThat(lock.entries()).containsExactly(
                    new LockEntryResponse("a", 1),
                    new LockEntryResponse("b", 1),
                    new LockEntryResponse("x", 1));
            assertJointLockEquals(service.getJointLock(lock.id()), lock);
        } else {
            // 撤回先提交：期望版本过期，联合锁必须 409，且无半成品。
            assertThat(lockOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) lockOutcome).getStatus()).isEqualTo(409);
            assertNoLockRows();
        }
    }

    @Test
    void concurrentSameJointRequestIdResolvesToSingleLock() throws Exception {
        service.registerArtifact(requestId(), artifact("a", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("b", 1, dep("x", 1, 2)));
        service.registerArtifact(requestId(), artifact("x", 2));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<JointLockFileResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.createJointLock(rid, joint(3L, root("a", 1), root("b", 1)));
            }));
        }
        pool.shutdown();

        JointLockFileResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<JointLockFileResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        // 竞争不泄露唯一键异常，最终只有一份锁文件与一条幂等记录。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM lock_file", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_root WHERE lock_file_id = ?",
                Integer.class, first.id())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    /**
     * 断言数据库回读的联合锁与创建响应一致；
     * created_at 为 TIMESTAMP(6)，回读时间戳按微秒四舍五入，按微秒容差比较。
     */
    private static void assertJointLockEquals(JointLockFileResponse actual,
                                              JointLockFileResponse expected) {
        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.roots()).isEqualTo(expected.roots());
        assertThat(actual.repositoryVersion()).isEqualTo(expected.repositoryVersion());
        assertThat(actual.entries()).isEqualTo(expected.entries());
        assertThat(actual.edges()).isEqualTo(expected.edges());
        assertThat(actual.createdAt())
                .isCloseTo(expected.createdAt(), within(1, ChronoUnit.MICROS));
    }
}
