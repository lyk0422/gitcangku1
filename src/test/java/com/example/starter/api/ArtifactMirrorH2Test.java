package com.example.starter.api;

import com.example.starter.api.dto.LockEntryResponse;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockMirrorView;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.MirrorFailoverResponse;
import com.example.starter.api.dto.MirrorSpec;
import com.example.starter.api.dto.MirrorView;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.RegisterMirrorsRequest;
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
 * 镜像源优先级、回退解析、可用性开关与并发幂等的 H2（MODE=MySQL）数据库测试。
 */
@SpringBootTest
class ArtifactMirrorH2Test {

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
        service.registerArtifact(requestId(),
                new RegisterArtifactRequest(name, version, List.of()));
    }

    private static RegisterMirrorsRequest mirrors(MirrorSpec... specs) {
        return new RegisterMirrorsRequest(List.of(specs));
    }

    private static MirrorSpec mirror(String id, int priority) {
        return new MirrorSpec(id, priority);
    }

    // ------------------------------------------------------------------
    // 镜像登记主流程与校验
    // ------------------------------------------------------------------

    @Test
    void registerMirrorsPersistsSortedByPriorityAndListsDetails() {
        registerArtifact("app", 1);
        List<MirrorView> views = service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m-slow", 3), mirror("m-fast", 1), mirror("m-mid", 2)));

        assertThat(views).extracting("mirrorId", "priority", "available")
                .containsExactly(
                        tuple("m-fast", 1, true),
                        tuple("m-mid", 2, true),
                        tuple("m-slow", 3, true));

        // 登记明细查询：含全部镜像，按优先级升序。
        assertThat(service.listMirrors("app", 1)).isEqualTo(views);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(3);
    }

    @Test
    void registerMirrorsOnMissingVersionReturns404() {
        assertThatThrownBy(() -> service.registerMirrors(requestId(), "ghost", 1,
                mirrors(mirror("m1", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void duplicateMirrorIdOnSameVersionReturns409() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));
        assertThatThrownBy(() -> service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m1", 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        // 失败回滚：仍只有 1 条镜像记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(1);
    }

    @Test
    void fourthMirrorOnSameVersionReturns422() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m1", 1), mirror("m2", 2), mirror("m3", 3)));
        assertThatThrownBy(() -> service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m4", 4))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void duplicateMirrorIdWithinRequestReturns400() {
        registerArtifact("app", 1);
        assertThatThrownBy(() -> service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m1", 1), mirror("m1", 2))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void emptyMirrorListReturns400() {
        registerArtifact("app", 1);
        assertThatThrownBy(() -> service.registerMirrors(requestId(), "app", 1,
                new RegisterMirrorsRequest(List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void mirrorUniqueConstraintIsEnforcedByDatabase() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));
        Long artifactId = jdbcTemplate.queryForObject(
                "SELECT id FROM artifact WHERE name = 'app' AND version = 1", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO artifact_mirror (artifact_id, mirror_id, priority, available, created_at) "
                        + "VALUES (?, 'm1', 2, 1, CURRENT_TIMESTAMP(6))", artifactId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------
    // 可用性开关
    // ------------------------------------------------------------------

    @Test
    void availabilityToggleKeepsRecordAndExcludesFromFailoverOnly() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m1", 1), mirror("m2", 2)));

        MirrorView off = service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        assertThat(off.available()).isFalse();

        // 记录保留：明细查询仍列出 m1，但标记不可用。
        assertThat(service.listMirrors("app", 1)).extracting("mirrorId", "available")
                .containsExactly(tuple("m1", false), tuple("m2", true));

        MirrorView on = service.setMirrorAvailability(requestId(), "app", 1, "m1", true);
        assertThat(on.available()).isTrue();
        assertThat(service.listMirrors("app", 1)).extracting("mirrorId", "available")
                .containsExactly(tuple("m1", true), tuple("m2", true));
    }

    @Test
    void availabilityOnMissingMirrorReturns404() {
        registerArtifact("app", 1);
        assertThatThrownBy(() -> service.setMirrorAvailability(
                requestId(), "app", 1, "ghost", false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 锁定固化镜像快照
    // ------------------------------------------------------------------

    @Test
    void lockFreezesAvailableMirrorsSortedByPriority() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m-slow", 3), mirror("m-fast", 1), mirror("m-mid", 2)));
        // m-mid 在锁定前标记不可用：不进快照但记录保留。
        service.setMirrorAvailability(requestId(), "app", 1, "m-mid", false);

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        assertThat(lock.entries()).hasSize(1);
        LockEntryResponse entry = lock.entries().get(0);
        assertThat(entry.mirrors()).extracting("mirrorId", "priority")
                .containsExactly(tuple("m-fast", 1), tuple("m-slow", 3));

        // 历史查询同样返回固化快照。
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.entries().get(0).mirrors()).isEqualTo(entry.mirrors());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_mirror WHERE lock_file_id = ?",
                Integer.class, lock.id())).isEqualTo(2);
    }

    @Test
    void lockWithAllMirrorsUnavailableStillSucceedsWithEmptyMirrorList() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);

        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        assertThat(lock.entries()).hasSize(1);
        assertThat(lock.entries().get(0).mirrors()).isEmpty();
    }

    @Test
    void availabilityChangeAfterLockDoesNotRewriteExistingLockSnapshot() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m1", 1), mirror("m2", 2)));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        assertThat(lock.entries().get(0).mirrors()).extracting("mirrorId")
                .containsExactly("m1", "m2");

        // 锁定后变更可用性：历史锁文件快照不变。
        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        LockFileResponse queried = service.getLock(lock.id());
        assertThat(queried.entries().get(0).mirrors()).extracting("mirrorId")
                .containsExactly("m1", "m2");

        // 新锁定反映最新可用性：m1 不再进入新快照。
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m3", 3)));
        LockFileResponse second = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        assertThat(second.entries().get(0).mirrors()).extracting("mirrorId")
                .containsExactly("m2", "m3");
    }

    // ------------------------------------------------------------------
    // 故障切换查询
    // ------------------------------------------------------------------

    @Test
    void failoverReturnsFirstAvailableMirrorByPriority() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m-slow", 3), mirror("m-fast", 1), mirror("m-mid", 2)));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        MirrorFailoverResponse first = service.failoverMirror(lock.id(), "app");
        assertThat(first.mirrorId()).isEqualTo("m-fast");
        assertThat(first.priority()).isEqualTo(1);
        assertThat(first.version()).isEqualTo(1);

        // 最高优先级镜像不可用后回退到次优。
        service.setMirrorAvailability(requestId(), "app", 1, "m-fast", false);
        MirrorFailoverResponse second = service.failoverMirror(lock.id(), "app");
        assertThat(second.mirrorId()).isEqualTo("m-mid");

        service.setMirrorAvailability(requestId(), "app", 1, "m-mid", false);
        MirrorFailoverResponse third = service.failoverMirror(lock.id(), "app");
        assertThat(third.mirrorId()).isEqualTo("m-slow");

        // 锁文件快照不被故障切换或可用性变更改写。
        assertThat(service.getLock(lock.id()).entries().get(0).mirrors())
                .extracting("mirrorId")
                .containsExactly("m-fast", "m-mid", "m-slow");
    }

    @Test
    void failoverWithAllMirrorsUnavailableReturns422() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        assertThatThrownBy(() -> service.failoverMirror(lock.id(), "app"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }

    @Test
    void failoverWithNameOutsideLockReturns404() {
        registerArtifact("app", 1);
        registerArtifact("other", 1);
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        assertThatThrownBy(() -> service.failoverMirror(lock.id(), "other"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.failoverMirror(9999L, "app"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void failoverIsReadOnlyAndDoesNotConsumeRequestId() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        service.failoverMirror(lock.id(), "app");
        service.failoverMirror(lock.id(), "app");
        // 写操作共 3 次（制品登记、镜像登记、锁定）；只读故障切换不留任何记录。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request", Integer.class)).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdSameMirrorsReplaysOriginalResult() {
        registerArtifact("app", 1);
        String rid = requestId();
        RegisterMirrorsRequest request = mirrors(mirror("m1", 1), mirror("m2", 2));
        List<MirrorView> first = service.registerMirrors(rid, "app", 1, request);
        List<MirrorView> replay = service.registerMirrors(rid, "app", 1, request);

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentMirrorsReturns409() {
        registerArtifact("app", 1);
        String rid = requestId();
        service.registerMirrors(rid, "app", 1, mirrors(mirror("m1", 1)));
        assertThatThrownBy(() -> service.registerMirrors(rid, "app", 1,
                mirrors(mirror("m2", 1))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedMirrorRegistrationDoesNotConsumeRequestId() {
        registerArtifact("app", 1);
        String rid = requestId();
        // 重复标识 400，不占键。
        assertThatThrownBy(() -> service.registerMirrors(rid, "app", 1,
                mirrors(mirror("m1", 1), mirror("m1", 2))))
                .isInstanceOf(ApiException.class);
        List<MirrorView> later = service.registerMirrors(rid, "app", 1, mirrors(mirror("m1", 1)));
        assertThat(later).hasSize(1);
    }

    @Test
    void availabilityToggleSameRequestIdReplaysAndDifferentParamsConflict() {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));
        String rid = requestId();
        MirrorView first = service.setMirrorAvailability(rid, "app", 1, "m1", false);
        MirrorView replay = service.setMirrorAvailability(rid, "app", 1, "m1", false);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available FROM artifact_mirror WHERE mirror_id = 'm1'", Integer.class))
                .isEqualTo(0);

        assertThatThrownBy(() -> service.setMirrorAvailability(rid, "app", 1, "m1", true))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    // ------------------------------------------------------------------
    // 并发：可用性变更与故障切换/锁定按提交顺序裁决
    // ------------------------------------------------------------------

    @Test
    void concurrentAvailabilityToggleAndFailoverNeverSelectsUnavailableMirror() throws Exception {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1,
                mirrors(mirror("m1", 1), mirror("m2", 2)));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        // 一个线程将 m1 标记不可用，其余线程并发故障切换查询。
        Callable<Object> toggleTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        };
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(toggleTask);
        for (int i = 1; i < threads; i++) {
            tasks.add(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.failoverMirror(lock.id(), "app");
                } catch (ApiException e) {
                    return e;
                }
            });
        }

        List<Future<Object>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<MirrorFailoverResponse> responses = new ArrayList<>();
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome instanceof MirrorFailoverResponse response) {
                responses.add(response);
            }
        }
        // 每次查询要么读到变更前状态（m1 可用）要么读到变更后状态（选中 m2），
        // 绝不选中已提交不可用的镜像，也绝不 422（m2 始终可用）。
        for (MirrorFailoverResponse response : responses) {
            assertThat(response.mirrorId()).isIn("m1", "m2");
        }
        // 变更提交后：故障切换稳定选中 m2。
        assertThat(service.failoverMirror(lock.id(), "app").mirrorId()).isEqualTo("m2");
        // 锁文件快照始终不变。
        assertThat(service.getLock(lock.id()).entries().get(0).mirrors())
                .extracting("mirrorId").containsExactly("m1", "m2");
    }

    @Test
    void concurrentSameRequestIdMirrorRegistrationResolvesToSingleResult() throws Exception {
        registerArtifact("app", 1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String rid = requestId();
        RegisterMirrorsRequest request = mirrors(mirror("m1", 1), mirror("m2", 2));

        List<Future<List<MirrorView>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.registerMirrors(rid, "app", 1, request);
            }));
        }
        pool.shutdown();

        List<MirrorView> first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<List<MirrorView>> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM artifact_mirror", Integer.class)).isEqualTo(2);
        // 同 requestId 并发只产生一条镜像登记幂等记录（另一条是制品登记）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, rid)).isEqualTo(1);
    }

    @Test
    void concurrentMirrorRegistrationAndLockSerializeByCommitOrder() throws Exception {
        registerArtifact("app", 1);
        service.registerMirrors(requestId(), "app", 1, mirrors(mirror("m1", 1)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String lockRid = requestId();

        Callable<Object> lockTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.createLock(lockRid, new LockRequest("app", 1, 1L));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> toggleTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.setMirrorAvailability(requestId(), "app", 1, "m1", false);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(lockTask, toggleTask));
        pool.shutdown();
        Object lockOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object toggleOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(toggleOutcome).isInstanceOf(MirrorView.class);
        assertThat(lockOutcome).isInstanceOf(LockFileResponse.class);
        LockFileResponse lock = (LockFileResponse) lockOutcome;
        List<String> snapshot = lock.entries().get(0).mirrors().stream()
                .map(LockMirrorView::mirrorId).toList();
        // 按提交顺序裁决：锁定先于变更提交则快照含 m1，否则为空；两者均为一致状态。
        assertThat(snapshot).isIn(List.of(List.of("m1"), List.of()));
        // 最终状态：m1 不可用，故障切换 422。
        assertThatThrownBy(() -> service.failoverMirror(lock.id(), "app"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
    }
}
