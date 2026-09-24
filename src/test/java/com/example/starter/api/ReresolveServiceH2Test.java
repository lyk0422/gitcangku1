package com.example.starter.api;

import com.example.starter.api.dto.ArtifactResponse;
import com.example.starter.api.dto.DependencySpec;
import com.example.starter.api.dto.LockFileResponse;
import com.example.starter.api.dto.LockRequest;
import com.example.starter.api.dto.RegisterArtifactRequest;
import com.example.starter.api.dto.ReresolveDiffResponse;
import com.example.starter.api.dto.ReresolveReportResponse;
import com.example.starter.api.dto.ReresolveRequest;
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
 * 锁文件重解析的 H2（MODE=MySQL）集成测试：
 * 三种结论、失败分支、原锁不可改、报告不可变、reresolveKey/requestId 幂等与真实并发裁决。
 */
@SpringBootTest
class ReresolveServiceH2Test {

    @Autowired
    private ArtifactService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM reresolve_report_diff");
        jdbcTemplate.update("DELETE FROM reresolve_report_entry");
        jdbcTemplate.update("DELETE FROM reresolve_report");
        jdbcTemplate.update("DELETE FROM lock_file_entry");
        jdbcTemplate.update("DELETE FROM lock_file");
        jdbcTemplate.update("DELETE FROM artifact_dependency");
        jdbcTemplate.update("DELETE FROM artifact");
        jdbcTemplate.update("DELETE FROM idempotent_request");
        jdbcTemplate.update("UPDATE repository_state SET version = 0 WHERE id = 1");
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private long currentRepositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // 三种结论
    // ------------------------------------------------------------------

    @Test
    void reproducibleWhenResolutionIsIdentical() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));

        ReresolveReportResponse report = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-repro-1", 2L));

        assertThat(report.conclusion()).isEqualTo("REPRODUCIBLE");
        assertThat(report.lockFileId()).isEqualTo(lock.id());
        assertThat(report.repositoryVersion()).isEqualTo(2L);
        assertThat(report.newEntries()).isEqualTo(lock.entries());
        assertThat(report.diffs()).isEmpty();
    }

    @Test
    void driftedBySupersededHigherVersion() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        service.registerArtifact(uuid(), artifact("lib", 2));
        assertThat(currentRepositoryVersion()).isEqualTo(3L);

        ReresolveReportResponse report = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-drift-higher", 3L));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.repositoryVersion()).isEqualTo(3L);
        assertThat(report.newEntries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
        assertThat(report.diffs()).hasSize(1);
        ReresolveDiffResponse diff = report.diffs().get(0);
        assertThat(diff.name()).isEqualTo("lib");
        assertThat(diff.changeType()).isEqualTo("VERSION_CHANGED");
        assertThat(diff.originalVersion()).isEqualTo(1);
        assertThat(diff.newVersion()).isEqualTo(2);
        assertThat(diff.reason()).isEqualTo("SUPERSEDED_BY_HIGHER");
    }

    @Test
    void driftedByWithdrawnOriginalAndRemovedTransitiveName() {
        // app[lib1..2]；lib2 -> util[1,1]；lib1 无依赖。锁定取 lib2+util1。
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 2, dep("util", 1, 1)));
        service.registerArtifact(uuid(), artifact("util", 1));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));

        // 撤回 lib2 后回溯落到 lib1，util 随之移除。
        service.withdrawArtifact(uuid(), "lib", 2);
        long versionAtReresolve = currentRepositoryVersion();

        ReresolveReportResponse report = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-drift-withdrawn", versionAtReresolve));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.newEntries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        assertThat(report.diffs()).extracting("name", "changeType", "originalVersion",
                        "newVersion", "reason")
                .containsExactly(
                        tuple("lib", "VERSION_CHANGED", 2, 1, "ORIGINAL_WITHDRAWN"),
                        // util1 并未撤回，只是 lib1 不再要求它 → 区间要求消失。
                        tuple("util", "REMOVED", 1, null, "RANGE_NO_LONGER_SATISFIED"));
    }

    @Test
    void driftedByAddedTransitiveName() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));

        // 新登记 lib2（引入 util）与 util1：重解析取 lib2，util 为新增。
        service.registerArtifact(uuid(), artifact("lib", 2, dep("util", 1, 1)));
        service.registerArtifact(uuid(), artifact("util", 1));
        long versionAtReresolve = currentRepositoryVersion();

        ReresolveReportResponse report = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-drift-added", versionAtReresolve));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.newEntries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2), tuple("util", 1));
        assertThat(report.diffs()).extracting("name", "changeType", "reason")
                .containsExactly(
                        tuple("lib", "VERSION_CHANGED", "SUPERSEDED_BY_HIGHER"),
                        tuple("util", "ADDED", "SUPERSEDED_BY_HIGHER"));
    }

    @Test
    void infeasibleWhenAllCandidatesWithdrawn() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        service.withdrawArtifact(uuid(), "lib", 1);
        long versionAtReresolve = currentRepositoryVersion();

        ReresolveReportResponse report = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-infeasible-withdrawn", versionAtReresolve));

        assertThat(report.conclusion()).isEqualTo("INFEASIBLE");
        assertThat(report.repositoryVersion()).isEqualTo(versionAtReresolve);
        assertThat(report.newEntries()).isEmpty();
        assertThat(report.diffs()).hasSize(1);
        ReresolveDiffResponse diff = report.diffs().get(0);
        assertThat(diff.changeType()).isEqualTo("INFEASIBLE");
        assertThat(diff.name()).isEqualTo("lib");
        assertThat(diff.originalVersion()).isEqualTo(1);
        assertThat(diff.newVersion()).isNull();
        assertThat(diff.reason()).isEqualTo("VERSIONS_WITHDRAWN");
        assertThat(diff.detail()).contains("1");
    }

    @Test
    void infeasibleWhenNewlyChosenVersionRequiresMissingName() {
        // 锁定 lib1；登记依赖不存在 newdep 的 lib2，再撤回 lib1 使回溯无退路 → 不可行。
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        service.registerArtifact(uuid(), artifact("lib", 2, dep("newdep", 1, 1)));
        service.withdrawArtifact(uuid(), "lib", 1);
        long versionAtReresolve = currentRepositoryVersion();

        ReresolveReportResponse report = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-infeasible-missing", versionAtReresolve));

        assertThat(report.conclusion()).isEqualTo("INFEASIBLE");
        assertThat(report.diffs()).hasSize(1);
        ReresolveDiffResponse diff = report.diffs().get(0);
        assertThat(diff.name()).isEqualTo("newdep");
        assertThat(diff.reason()).isEqualTo("VERSIONS_MISSING");
        assertThat(diff.originalVersion()).isNull();
        assertThat(diff.detail()).contains("newdep").contains("1");
    }

    // ------------------------------------------------------------------
    // 原锁不可改、不新增锁文件、报告独立不可变
    // ------------------------------------------------------------------

    @Test
    void reresolveNeverModifiesOriginalLockAndCreatesNoNewLock() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse before = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        service.registerArtifact(uuid(), artifact("lib", 2));

        service.reresolve(uuid(), before.id(), new ReresolveRequest("key-immutable-1", 3L));
        // 同一锁文件可多次重解析，各报告独立。
        ReresolveReportResponse second = service.reresolve(uuid(), before.id(),
                new ReresolveRequest("key-immutable-2", 3L));

        LockFileResponse after = service.getLock(before.id());
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.rootName()).isEqualTo(before.rootName());
        assertThat(after.rootVersion()).isEqualTo(before.rootVersion());
        assertThat(after.repositoryVersion()).isEqualTo(before.repositoryVersion());
        // 经 TIMESTAMP(6) 存储后纳秒被四舍五入到微秒，允许 1 微秒误差。
        assertThat(after.createdAt()).isCloseTo(before.createdAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(after.entries()).isEqualTo(before.entries());
        assertThat(after.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        assertThat(service.listLocks()).hasSize(1);

        // 两份报告均独立持久化且内容稳定（不可变）。
        ReresolveReportResponse firstReloaded = service.getReresolveReport(
                service.listReresolveReports(before.id()).get(0).id());
        assertThat(firstReloaded.id()).isNotEqualTo(second.id());
        assertThat(firstReloaded.conclusion()).isEqualTo("DRIFTED");
        assertThat(second.conclusion()).isEqualTo("DRIFTED");
        assertThat(service.listReresolveReports(before.id()))
                .extracting("reresolveKey")
                .containsExactly("key-immutable-1", "key-immutable-2");
    }

    @Test
    void reportPersistsForEveryConclusionAndQueriesAreConsistent() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));

        ReresolveReportResponse repro = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-q-1", 2L));
        service.registerArtifact(uuid(), artifact("lib", 2));
        ReresolveReportResponse drift = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-q-2", 3L));
        service.withdrawArtifact(uuid(), "lib", 2);
        service.withdrawArtifact(uuid(), "lib", 1);
        ReresolveReportResponse infeasible = service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-q-3", currentRepositoryVersion()));

        List<ReresolveReportResponse> reports = service.listReresolveReports(lock.id());
        assertThat(reports).extracting("id", "conclusion")
                .containsExactly(
                        tuple(repro.id(), "REPRODUCIBLE"),
                        tuple(drift.id(), "DRIFTED"),
                        tuple(infeasible.id(), "INFEASIBLE"));
        // 单查与列表内容一致（时间戳经 TIMESTAMP(6) 存储后单独比较到微秒）。
        ReresolveReportResponse driftReloaded = service.getReresolveReport(drift.id());
        assertThat(driftReloaded.id()).isEqualTo(drift.id());
        assertThat(driftReloaded.reresolveKey()).isEqualTo(drift.reresolveKey());
        assertThat(driftReloaded.lockFileId()).isEqualTo(drift.lockFileId());
        assertThat(driftReloaded.repositoryVersion()).isEqualTo(drift.repositoryVersion());
        assertThat(driftReloaded.conclusion()).isEqualTo(drift.conclusion());
        assertThat(driftReloaded.newEntries()).isEqualTo(drift.newEntries());
        assertThat(driftReloaded.diffs()).isEqualTo(drift.diffs());
        // 明细按名称升序。
        assertThat(drift.diffs()).extracting("name").isSorted();
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void staleRepositoryVersionReturns409AndPersistsNothing() {
        service.registerArtifact(uuid(), artifact("app", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 1L));

        assertThatThrownBy(() -> service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-stale", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listReresolveReports(lock.id())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isZero();
        // 仓库版本不被重解析推进。
        assertThat(currentRepositoryVersion()).isEqualTo(1L);
    }

    @Test
    void missingLockFileReturns404() {
        assertThatThrownBy(() -> service.reresolve(uuid(), 9999L,
                new ReresolveRequest("key-missing-lock", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void withdrawnRootReturns422() {
        service.registerArtifact(uuid(), artifact("app", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 1L));
        service.withdrawArtifact(uuid(), "app", 1);

        assertThatThrownBy(() -> service.reresolve(uuid(), lock.id(),
                new ReresolveRequest("key-root-withdrawn", currentRepositoryVersion())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(service.listReresolveReports(lock.id())).isEmpty();
    }

    @Test
    void listReportsForMissingLockReturns404AndMissingReportReturns404() {
        assertThatThrownBy(() -> service.listReresolveReports(9999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.getReresolveReport(9999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameRequestIdReplaysFirstReportSnapshot() {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        service.registerArtifact(uuid(), artifact("lib", 2));

        String rid = uuid();
        ReresolveRequest request = new ReresolveRequest("key-replay", 3L);
        ReresolveReportResponse first = service.reresolve(rid, lock.id(), request);
        // 仓库继续前进后用同参重放：仍返回首次快照。
        service.withdrawArtifact(uuid(), "lib", 2);
        ReresolveReportResponse replay = service.reresolve(rid, lock.id(), request);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        assertThat(replay.repositoryVersion()).isEqualTo(3L);
        assertThat(replay.conclusion()).isEqualTo("DRIFTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        service.registerArtifact(uuid(), artifact("app", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 1L));

        String rid = uuid();
        service.reresolve(rid, lock.id(), new ReresolveRequest("key-rid-diff", 1L));
        assertThatThrownBy(() -> service.reresolve(rid, lock.id(),
                new ReresolveRequest("key-rid-diff", 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedReresolveConsumesNeitherRequestIdNorReresolveKey() {
        service.registerArtifact(uuid(), artifact("app", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 1L));

        String rid = uuid();
        // 期望版本错误 → 409 失败。
        assertThatThrownBy(() -> service.reresolve(rid, lock.id(),
                new ReresolveRequest("key-reuse-after-fail", 5L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));

        // 同一 requestId 与同一 reresolveKey 均可被后续成功请求使用。
        ReresolveReportResponse later = service.reresolve(rid, lock.id(),
                new ReresolveRequest("key-reuse-after-fail", 1L));
        assertThat(later.conclusion()).isEqualTo("REPRODUCIBLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report WHERE reresolve_key = ?",
                Integer.class, "key-reuse-after-fail")).isEqualTo(1);
    }

    @Test
    void reresolveKeyIsGloballyUniqueAcrossLockFiles() {
        service.registerArtifact(uuid(), artifact("app", 1));
        service.registerArtifact(uuid(), artifact("other", 1));
        // 两次登记后仓库版本为 2；锁定不推进版本，两个锁均期望 2。
        LockFileResponse first = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        LockFileResponse second = service.createLock(uuid(), new LockRequest("other", 1, 2L));

        service.reresolve(uuid(), first.id(), new ReresolveRequest("shared-key", 2L));
        // 即使 requestId 不同，reresolveKey 跨锁文件全局唯一 → 409。
        assertThatThrownBy(() -> service.reresolve(uuid(), second.id(),
                new ReresolveRequest("shared-key", 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(service.listReresolveReports(second.id())).isEmpty();
    }

    @Test
    void concurrentSameReresolveKeyResolvesToSingleReport() throws Exception {
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String key = "key-concurrent-" + uuid();

        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    return service.reresolve(uuid(), lock.id(), new ReresolveRequest(key, 2L));
                } catch (ApiException e) {
                    return e;
                }
            }));
        }
        pool.shutdown();

        int success = 0;
        int conflict = 0;
        ReresolveReportResponse winner = null;
        for (Future<Object> future : futures) {
            Object outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome instanceof ReresolveReportResponse report) {
                success++;
                winner = report;
            } else {
                conflict++;
                assertThat(((ApiException) outcome).getStatus()).isEqualTo(409);
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(winner).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report WHERE reresolve_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发：重解析与撤回按提交顺序裁决，快照必须自洽
    // ------------------------------------------------------------------

    @Test
    void concurrentReresolveAndWithdrawProducesSelfConsistentReport() throws Exception {
        // app[lib1..1]，lib1；锁定于版本 2。
        service.registerArtifact(uuid(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(uuid(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(uuid(), new LockRequest("app", 1, 2L));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Object> reresolveTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.reresolve(uuid(), lock.id(),
                        new ReresolveRequest("key-race-" + uuid(), versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.withdrawArtifact(uuid(), "lib", 1);
            } catch (ApiException e) {
                return e;
            }
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(reresolveTask, withdrawTask));
        pool.shutdown();
        Object reresolveOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        long finalVersion = currentRepositoryVersion();
        assertThat(finalVersion).isEqualTo(versionBefore + 1);

        if (reresolveOutcome instanceof ReresolveReportResponse report) {
            // 重解析先提交：固化版本 2 的自洽快照，绝不能出现携带版本 3 却使用 lib1 的结论。
            assertThat(report.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(report.conclusion()).isEqualTo("REPRODUCIBLE");
            assertThat(report.newEntries()).extracting("name", "version")
                    .containsExactly(tuple("app", 1), tuple("lib", 1));
        } else {
            // 撤回先提交：期望版本过期，必须 409，且无报告残留。
            assertThat(((ApiException) reresolveOutcome).getStatus()).isEqualTo(409);
            assertThat(service.listReresolveReports(lock.id())).isEmpty();
        }

        // 无论谁先提交，所有已持久化报告都不允许出现“新版本号 + 已撤回版本”的矛盾组合。
        List<ReresolveReportResponse> reports = service.listReresolveReports(lock.id());
        for (ReresolveReportResponse report : reports) {
            boolean usesWithdrawnLib = report.newEntries().stream()
                    .anyMatch(e -> e.name().equals("lib") && e.version() == 1);
            if (report.repositoryVersion() == finalVersion) {
                assertThat(usesWithdrawnLib).isFalse();
            }
        }
        // 原锁文件始终未被改写。
        assertThat(service.getLock(lock.id()).entries()).isEqualTo(lock.entries());
    }
}
