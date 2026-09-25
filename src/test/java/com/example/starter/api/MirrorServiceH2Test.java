package com.example.starter.api;

import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorDetailView;
import com.example.starter.api.dto.MirrorResponse;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorRequest;
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
 * 镜像源优先级、锁定固化、故障切换、可用性开关与并发幂等的 H2（MODE=MySQL）测试：
 * 镜像规则与锁文件固化均由真实数据库事务与唯一约束验证。
 */
@SpringBootTest
class MirrorServiceH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM lock_file_mirror");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_mirror");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private void registerArtifact(String name, int version) {
        service.registerArtifact(requestId(), new RegisterArtifactRequest(name, version, List.of()));
    }

    private void registerArtifactWithDep(String name, int version, String depName, int min, int max) {
        service.registerArtifact(requestId(), new RegisterArtifactRequest(
                name, version,
                List.of(new com.example.starter.api.dto.DependencySpec(depName, min, max))));
    }

    private MirrorResponse registerMirror(String name, int version, String mirrorId, int priority) {
        return service.registerMirror(requestId(), name, version,
                new RegisterMirrorRequest(mirrorId, priority));
    }

    /** 登记 app:1（无依赖），期望版本自动取当前仓库版本，返回其锁定响应。 */
    private LockFileResponse lockApp() {
        long currentVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
        return service.createLock(requestId(), new LockRequest("app", 1, currentVersion));
    }

    // ------------------------------------------------------------------
    // 登记主流程与明细查询
    // ------------------------------------------------------------------

    @Test
    void registerMirrorAdvancesVersionAndDetailQuerySortsByPriority() {
        registerArtifact("app", 1);
        MirrorResponse m3 = registerMirror("app", 1, "mirror-c", 3);
        MirrorResponse m1 = registerMirror("app", 1, "mirror-a", 1);
        MirrorResponse m2 = registerMirror("app", 1, "mirror-b", 2);

        assertThat(m1.available()).isTrue();
        // 明细查询按优先级升序，且保留全部记录。
        List<MirrorDetailView> details = service.listMirrors("app", 1);
        assertThat(details).extracting("mirrorId", "priority", "available")
                .containsExactly(tuple("mirror-a", 1, true),
                        tuple("mirror-b", 2, true),
                        tuple("mirror-c", 3, true));
        assertThat(details).allSatisfy(d -> assertThat(d.createdAt()).isNotNull());
        // 登记顺序：app(版本1) -> c(2) -> a(3) -> b(4)。
        assertThat(m3.repositoryVersion()).isEqualTo(2);
        assertThat(m1.repositoryVersion()).isEqualTo(3);
        assertThat(m2.repositoryVersion()).isEqualTo(4);
    }

    @Test
    void duplicateMirrorOnSameVersionReturns409() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        assertThatThrownBy(() -> registerMirror("app", 1, "m1", 2))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 冲突登记不推进版本。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @Test
    void sameMirrorIdCanBeRegisteredOnDifferentVersions() {
        registerArtifact("app", 1);
        registerArtifact("app", 2);
        registerMirror("app", 1, "shared", 1);
        MirrorResponse second = registerMirror("app", 2, "shared", 1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(2);
    }

    @Test
    void fourthMirrorReturns422() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        registerMirror("app", 1, "m3", 3);
        assertThatThrownBy(() -> registerMirror("app", 1, "m4", 4))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror WHERE artifact_id = "
                        + "(SELECT id FROM artifact WHERE name='app' AND version=1)",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void duplicatePriorityReturns422() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        assertThatThrownBy(() -> registerMirror("app", 1, "m2", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(422);
                            assertThat(e.getMessage()).contains("优先级");
                        });
    }

    @Test
    void registerMirrorOnMissingVersionReturns404() {
        assertThatThrownBy(() -> registerMirror("ghost", 9, "m1", 1))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void mirrorUniqueConstraintIsEnforcedByDatabase() {
        registerArtifact("app", 1);
        jdbcTemplate.update("INSERT INTO artifact_mirror (artifact_id, mirror_id, priority, available, created_at) "
                + "SELECT id, 'm1', 1, 1, CURRENT_TIMESTAMP(6) FROM artifact WHERE name='app' AND version=1");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_mirror (artifact_id, mirror_id, priority, available, created_at) "
                        + "SELECT id, 'm1', 2, 1, CURRENT_TIMESTAMP(6) FROM artifact WHERE name='app' AND version=1"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------
    // 可用性开关
    // ------------------------------------------------------------------

    @Test
    void markUnavailableThenAvailableTogglesFlagAndAdvancesVersion() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);

        MirrorResponse off = service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        assertThat(off.available()).isFalse();
        assertThat(off.repositoryVersion()).isEqualTo(3);
        assertThat(service.listMirrors("app", 1)).extracting("mirrorId", "available")
                .containsExactly(tuple("m1", false));

        MirrorResponse on = service.setMirrorAvailability(requestId(), "app", 1, "m1", true);
        assertThat(on.available()).isTrue();
        assertThat(on.repositoryVersion()).isEqualTo(4);
    }

    @Test
    void unavailableRecordIsRetainedAndExcludedFromResolution() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> service.resolveMirror(lockApp().id(), "app"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void doubleToggleSameStateReturns409WithoutAdvancingVersion() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        long versionAfter = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        assertThatThrownBy(() -> service.setMirrorAvailability(requestId(), "app", 1, "m1", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionAfter);
    }

    @Test
    void toggleMissingMirrorReturns404AndMissingVersionReturns404() {
        registerArtifact("app", 1);
        assertThatThrownBy(() -> service.setMirrorAvailability(requestId(), "app", 1, "ghost", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.setMirrorAvailability(requestId(), "ghost", 1, "m1", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 锁定固化镜像清单
    // ------------------------------------------------------------------

    @Test
    void lockFreezesOnlyAvailableMirrorsSortedByPriority() {
        // app:1 依赖 lib[1,1]，两个制品各有镜像并混合可用状态。
        registerArtifactWithDep("app", 1, "lib", 1, 1);
        registerArtifact("lib", 1);
        registerMirror("app", 1, "app-m1", 1);
        registerMirror("app", 1, "app-m2", 2);
        registerMirror("lib", 1, "lib-m1", 1);
        registerMirror("lib", 1, "lib-m2", 2);
        // 最高优先级的 app-m1、lib-m1 标记不可用，锁定时须过滤。
        service.setMirrorAvailability(requestId(), "app", 1, "app-m1", false);
        service.setMirrorAvailability(requestId(), "lib", 1, "lib-m1", false);

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 8L));

        assertThat(lock.entries()).hasSize(2);
        assertThat(lock.entries().get(0).name()).isEqualTo("app");
        assertThat(lock.entries().get(0).version()).isEqualTo(1);
        assertThat(lock.entries().get(0).mirrors()).extracting("mirrorId", "priority")
                .containsExactly(tuple("app-m2", 2));
        assertThat(lock.entries().get(1).name()).isEqualTo("lib");
        assertThat(lock.entries().get(1).version()).isEqualTo(1);
        assertThat(lock.entries().get(1).mirrors()).extracting("mirrorId", "priority")
                .containsExactly(tuple("lib-m2", 2));

        // 锁文件镜像表确实固化，且不含不可用镜像。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_mirror", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_mirror WHERE mirror_id IN ('app-m1','lib-m1')",
                Integer.class)).isZero();
    }

    @Test
    void lockSucceedsWithEmptyMirrorListWhenAllMirrorsUnavailable() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        service.setMirrorAvailability(requestId(), "app", 1, "m2", false);

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 5L));
        assertThat(lock.entries()).hasSize(1);
        assertThat(lock.entries().get(0).mirrors()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_mirror", Integer.class)).isZero();
    }

    @Test
    void lockWithoutMirrorsHasEmptyMirrorLists() {
        registerArtifact("app", 1);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        assertThat(lock.entries().get(0).mirrors()).isEmpty();
    }

    @Test
    void availabilityChangesAfterLockDoNotRewriteFrozenMirrorList() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 3L));
        assertThat(lock.entries().get(0).mirrors()).hasSize(2);

        // 事后两个镜像均不可用：锁文件镜像清单保持不变。
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        service.setMirrorAvailability(requestId(), "app", 1, "m2", false);

        LockFileResponse reloaded = service.getLock(lock.id());
        assertThat(reloaded.entries().get(0).mirrors()).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_mirror WHERE lock_file_id = ?",
                Integer.class, lock.id())).isEqualTo(2);

        // 恢复一个并再锁定：新锁只固化当前可用镜像，历史锁仍不变。
        service.setMirrorAvailability(requestId(), "app", 1, "m1", true);
        LockFileResponse later = service.createLock(requestId(), new LockRequest("app", 1, 6L));
        assertThat(later.entries().get(0).mirrors()).extracting("mirrorId")
                .containsExactly("m1");
        assertThat(service.getLock(lock.id()).entries().get(0).mirrors()).hasSize(2);
    }

    // ------------------------------------------------------------------
    // 故障切换查询
    // ------------------------------------------------------------------

    @Test
    void failoverReturnsHighestPriorityAvailableMirror() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        long lockId = lockApp().id();

        assertThat(service.resolveMirror(lockId, "app"))
                .isEqualTo(new MirrorView("m1", 1));

        // 最高优先级不可用后，回退到次高。
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        assertThat(service.resolveMirror(lockId, "app"))
                .isEqualTo(new MirrorView("m2", 2));

        // 恢复后重新选 m1。
        service.setMirrorAvailability(requestId(), "app", 1, "m1", true);
        assertThat(service.resolveMirror(lockId, "app"))
                .isEqualTo(new MirrorView("m1", 1));
    }

    @Test
    void failoverAllUnavailableReturns422WithExplanation() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        long lockId = lockApp().id();
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);

        assertThatThrownBy(() -> service.resolveMirror(lockId, "app"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(422);
                    assertThat(e.getMessage()).contains("没有可用镜像源");
                });
    }

    @Test
    void failoverOnNameNotInLockReturns404() {
        registerArtifact("app", 1);
        long lockId = lockApp().id();
        assertThatThrownBy(() -> service.resolveMirror(lockId, "not-in-lock"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void failoverOnMissingLockReturns404() {
        assertThatThrownBy(() -> service.resolveMirror(9999, "app"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void failoverIsReadOnlyAndDoesNotRewriteLock() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        long versionBeforeQuery = jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);

        service.resolveMirror(lock.id(), "app");
        service.resolveMirror(lock.id(), "app");

        // 查询只读：仓库版本不变、锁文件镜像清单不变、无新增幂等记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class))
                .isEqualTo(versionBeforeQuery);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(3);
        LockFileResponse reloaded = service.getLock(lock.id());
        assertThat(reloaded.id()).isEqualTo(lock.id());
        assertThat(reloaded.entries()).isEqualTo(lock.entries());
    }

    // ------------------------------------------------------------------
    // 镜像写操作幂等
    // ------------------------------------------------------------------

    @Test
    void registerMirrorReplaysSameRequestIdAndParams() {
        registerArtifact("app", 1);
        String rid = requestId();
        MirrorResponse first = service.registerMirror(rid, "app", 1,
                new RegisterMirrorRequest("m1", 1));
        MirrorResponse replay = service.registerMirror(rid, "app", 1,
                new RegisterMirrorRequest("m1", 1));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(1);
    }

    @Test
    void registerMirrorSameRequestIdDifferentParamsReturns409() {
        registerArtifact("app", 1);
        String rid = requestId();
        service.registerMirror(rid, "app", 1, new RegisterMirrorRequest("m1", 1));
        assertThatThrownBy(() -> service.registerMirror(rid, "app", 1,
                new RegisterMirrorRequest("m2", 2)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void availabilityToggleReplaysAndOppositeDirectionWithSameKeyConflicts() {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        String rid = requestId();

        MirrorResponse first = service.setMirrorAvailability(rid, "app", 1, "m1", false);
        MirrorResponse replay = service.setMirrorAvailability(rid, "app", 1, "m1", false);
        assertThat(replay).isEqualTo(first);

        // 同键反向操作（异参）→ 409，状态不被翻转。
        assertThatThrownBy(() -> service.setMirrorAvailability(rid, "app", 1, "m1", true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listMirrors("app", 1).get(0).available()).isFalse();
    }

    @Test
    void failedMirrorCallDoesNotConsumeRequestId() {
        registerArtifact("app", 1);
        String rid = requestId();
        // 对不存在镜像切换（404）不应占用 requestId。
        assertThatThrownBy(() -> service.setMirrorAvailability(rid, "app", 1, "ghost", false))
                .isInstanceOf(ApiException.class);
        // 同键随后用于合法登记成功。
        MirrorResponse ok = service.registerMirror(rid, "app", 1,
                new RegisterMirrorRequest("m1", 1));
        assertThat(ok.mirrorId()).isEqualTo("m1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdRegisterMirrorResolvesOnce() throws Exception {
        registerArtifact("app", 1);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();

        List<Future<MirrorResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerMirror(rid, "app", 1,
                        new RegisterMirrorRequest("m1", 1));
            }));
        }
        pool.shutdown();

        MirrorResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<MirrorResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(1);
        // 准备阶段登记制品占一条，镜像并发 8 线程只应留下一条同键成功记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 可用性切换与故障切换的真实并发裁决
    // ------------------------------------------------------------------

    @Test
    void failoverAndUnavailableMarkConcurrencyNeverSelectsCommittedUnavailable() throws Exception {
        registerArtifact("app", 1);
        registerMirror("app", 1, "m1", 1);
        registerMirror("app", 1, "m2", 2);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 3L));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Object> queryTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.resolveMirror(lock.id(), "app");
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> markTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(queryTask, markTask));
        pool.shutdown();
        Object queryOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object markOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(markOutcome).isInstanceOf(MirrorResponse.class);
        // 按事务提交顺序裁决：查询先提交时 m1 尚可用可选 m1；标记先提交时必须回退 m2。
        assertThat(queryOutcome).isInstanceOf(MirrorView.class);
        MirrorView selected = (MirrorView) queryOutcome;
        assertThat(selected.mirrorId()).isIn("m1", "m2");

        // 标记提交后，后续任何查询都必须基于最新状态：m1 已不可用，只能选 m2。
        assertThat(service.resolveMirror(lock.id(), "app"))
                .isEqualTo(new MirrorView("m2", 2));
        // 并发期间锁文件固化清单不被改写：仍含 m1、m2。
        assertThat(service.getLock(lock.id()).entries().get(0).mirrors()).hasSize(2);
    }
}
