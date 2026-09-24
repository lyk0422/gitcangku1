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
 * 锁文件重解析的 H2 数据库测试：三种结论、差异原因、原锁不可改、
 * 报告不可变、失败分支、幂等重放与并发边界。
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

    private static String requestId() {
        return UUID.randomUUID().toString();
    }

    private static String reresolveKey() {
        return UUID.randomUUID().toString();
    }

    private RegisterArtifactRequest artifact(String name, int version, DependencySpec... deps) {
        return new RegisterArtifactRequest(name, version, List.of(deps));
    }

    private static DependencySpec dep(String name, int min, int max) {
        return new DependencySpec(name, min, max);
    }

    private long repositoryVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM repository_state WHERE id = 1", Long.class);
    }

    // ------------------------------------------------------------------
    // REPRODUCIBLE
    // ------------------------------------------------------------------

    @Test
    void reproducibleWhenRepositoryUnchanged() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 2));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 2L));

        assertThat(report.conclusion()).isEqualTo("REPRODUCIBLE");
        assertThat(report.lockFileId()).isEqualTo(lock.id());
        assertThat(report.repositoryVersion()).isEqualTo(2L);
        assertThat(report.entries()).isEqualTo(lock.entries());
        assertThat(report.diffs()).isEmpty();

        // 报告已固化：明细查询与列表查询返回相同内容。
        assertThat(service.getReresolveReport(report.id())).isEqualTo(report);
        assertThat(service.listReresolveReports(lock.id())).containsExactly(report);
        // 重解析不推进仓库版本，也不创建新锁文件。
        assertThat(repositoryVersion()).isEqualTo(2L);
        assertThat(service.listLocks()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // DRIFTED：三种差异原因与新增/移除
    // ------------------------------------------------------------------

    @Test
    void driftedWhenHigherVersionSupersedes() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        service.registerArtifact(requestId(), artifact("lib", 2));
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 3L));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
        assertThat(report.diffs()).extracting("name", "changeType", "reason",
                        "oldVersion", "newVersion")
                .containsExactly(tuple("lib", "CHANGED", "SUPERSEDED", 1, 2));
    }

    @Test
    void driftedWhenOriginalCandidateWithdrawn() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        service.registerArtifact(requestId(), artifact("lib", 2));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 3L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));

        service.withdrawArtifact(requestId(), "lib", 2);
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 4L));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        assertThat(report.diffs()).extracting("name", "changeType", "reason",
                        "oldVersion", "newVersion")
                .containsExactly(tuple("lib", "CHANGED", "WITHDRAWN", 2, 1));
    }

    @Test
    void driftedWhenRangeNoLongerSatisfied() {
        // app -> a[1,2] 且 b[1,2]；a1 无依赖；锁定为 a1 + b2。
        service.registerArtifact(requestId(),
                artifact("app", 1, dep("a", 1, 2), dep("b", 1, 2)));
        service.registerArtifact(requestId(), artifact("a", 1));
        service.registerArtifact(requestId(), artifact("b", 2));
        service.registerArtifact(requestId(), artifact("b", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("app", 1), tuple("b", 2));

        // 登记 a2 -> b[1,1]：重解析选 a2 后 b2 不再满足区间，降到 b1。
        service.registerArtifact(requestId(), artifact("a", 2, dep("b", 1, 1)));
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 5L));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 2), tuple("app", 1), tuple("b", 1));
        assertThat(report.diffs()).extracting("name", "changeType", "reason",
                        "oldVersion", "newVersion")
                .containsExactlyInAnyOrder(
                        tuple("a", "CHANGED", "SUPERSEDED", 1, 2),
                        tuple("b", "CHANGED", "RANGE_NOT_SATISFIED", 2, 1));
    }

    @Test
    void driftedWithRemovedName() {
        // app -> a[1,2]；a1 无依赖，a2 -> c[1,1]；锁定为 a2 + c1。
        service.registerArtifact(requestId(), artifact("app", 1, dep("a", 1, 2)));
        service.registerArtifact(requestId(), artifact("a", 1));
        service.registerArtifact(requestId(), artifact("a", 2, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("c", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 2), tuple("app", 1), tuple("c", 1));

        // 撤回 a2：重解析回退 a1，c 不再被需要。
        service.withdrawArtifact(requestId(), "a", 2);
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 5L));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("app", 1));
        assertThat(report.diffs()).extracting("name", "changeType", "reason",
                        "oldVersion", "newVersion")
                .containsExactlyInAnyOrder(
                        tuple("a", "CHANGED", "WITHDRAWN", 2, 1),
                        tuple("c", "REMOVED", "RANGE_NOT_SATISFIED", 1, null));
    }

    @Test
    void driftedWithAddedName() {
        // app -> a[1,2]；a1 无依赖；锁定为 a1。
        service.registerArtifact(requestId(), artifact("app", 1, dep("a", 1, 2)));
        service.registerArtifact(requestId(), artifact("a", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("app", 1));

        // 登记 a2 -> c[1,1] 与 c1：重解析选 a2 并新引入 c。
        service.registerArtifact(requestId(), artifact("a", 2, dep("c", 1, 1)));
        service.registerArtifact(requestId(), artifact("c", 1));
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 4L));

        assertThat(report.conclusion()).isEqualTo("DRIFTED");
        assertThat(report.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 2), tuple("app", 1), tuple("c", 1));
        assertThat(report.diffs()).extracting("name", "changeType", "reason",
                        "oldVersion", "newVersion")
                .containsExactlyInAnyOrder(
                        tuple("a", "CHANGED", "SUPERSEDED", 1, 2),
                        tuple("c", "ADDED", "RANGE_NOT_SATISFIED", null, 1));
    }

    // ------------------------------------------------------------------
    // INFEASIBLE
    // ------------------------------------------------------------------

    @Test
    void infeasibleWhenOnlyCandidateWithdrawn() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        service.withdrawArtifact(requestId(), "lib", 1);
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 3L));

        assertThat(report.conclusion()).isEqualTo("INFEASIBLE");
        assertThat(report.entries()).isEmpty();
        assertThat(report.diffs()).hasSize(1);
        ReresolveDiffResponse blocker = report.diffs().get(0);
        assertThat(blocker.name()).isEqualTo("lib");
        assertThat(blocker.changeType()).isEqualTo("BLOCKER");
        assertThat(blocker.reason()).isEqualTo("VERSION_WITHDRAWN");
        assertThat(blocker.minimumVersion()).isEqualTo(1);
        assertThat(blocker.maximumVersion()).isEqualTo(1);
        assertThat(blocker.versions()).containsExactly(1);

        // 阻塞明细已固化，查询结果一致。
        assertThat(service.getReresolveReport(report.id()).diffs()).isEqualTo(report.diffs());
    }

    @Test
    void infeasibleWhenRequiredVersionMissing() {
        // app -> a[1,2]；a1 -> b[1,1]，a2 -> b[2,2]；b 只有 b1：锁定为 a1 + b1。
        service.registerArtifact(requestId(), artifact("app", 1, dep("a", 1, 2)));
        service.registerArtifact(requestId(), artifact("a", 1, dep("b", 1, 1)));
        service.registerArtifact(requestId(), artifact("a", 2, dep("b", 2, 2)));
        service.registerArtifact(requestId(), artifact("b", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 4L));
        assertThat(lock.entries()).extracting("name", "version")
                .containsExactly(tuple("a", 1), tuple("app", 1), tuple("b", 1));

        // 撤回 a1：只剩 a2，但 a2 需要的 b2 从未登记 → 不可行且版本缺失。
        service.withdrawArtifact(requestId(), "a", 1);
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 5L));

        assertThat(report.conclusion()).isEqualTo("INFEASIBLE");
        assertThat(report.entries()).isEmpty();
        assertThat(report.diffs()).anySatisfy(diff -> {
            assertThat(diff.name()).isEqualTo("b");
            assertThat(diff.changeType()).isEqualTo("BLOCKER");
            assertThat(diff.reason()).isEqualTo("MISSING_VERSION");
            assertThat(diff.versions()).containsExactly(2);
        });
    }

    // ------------------------------------------------------------------
    // 失败分支
    // ------------------------------------------------------------------

    @Test
    void staleRepositoryVersionReturns409AndSavesNothing() {
        service.registerArtifact(requestId(), artifact("app", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        service.registerArtifact(requestId(), artifact("lib", 1));

        assertThatThrownBy(() -> service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 1L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isZero();
    }

    @Test
    void missingLockReturns404() {
        assertThatThrownBy(() -> service.reresolveLock(
                reresolveKey(), new ReresolveRequest(9999L, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void withdrawnRootReturns422() {
        service.registerArtifact(requestId(), artifact("app", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        service.withdrawArtifact(requestId(), "app", 1);

        assertThatThrownBy(() -> service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 2L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isZero();
    }

    @Test
    void missingReportReturns404() {
        assertThatThrownBy(() -> service.getReresolveReport(9999L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    // ------------------------------------------------------------------
    // 原锁不可改、报告不可变
    // ------------------------------------------------------------------

    @Test
    void originalLockIsNeverModifiedByReresolve() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        service.registerArtifact(requestId(), artifact("lib", 2));
        ReresolveReportResponse report = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 3L));
        assertThat(report.conclusion()).isEqualTo("DRIFTED");

        // 原锁文件内容与仓库版本保持锁定时的快照。
        LockFileResponse after = service.getLock(lock.id());
        assertThat(after).isEqualTo(lock);
        assertThat(after.entries()).extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM lock_file_entry", Integer.class)).isEqualTo(2);
    }

    @Test
    void reportsAreImmutableAndIndependentAcrossReresolves() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 3)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        service.registerArtifact(requestId(), artifact("lib", 2));
        ReresolveReportResponse first = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 3L));
        service.registerArtifact(requestId(), artifact("lib", 3));
        ReresolveReportResponse second = service.reresolveLock(
                reresolveKey(), new ReresolveRequest(lock.id(), 4L));

        // 同一锁文件可多次重解析，各报告独立且内容不回溯改写。
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(first.repositoryVersion()).isEqualTo(3L);
        assertThat(second.repositoryVersion()).isEqualTo(4L);
        assertThat(service.listReresolveReports(lock.id()))
                .containsExactly(first, second);
        assertThat(service.getReresolveReport(first.id()).entries())
                .extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 2));
        assertThat(service.getReresolveReport(second.id()).entries())
                .extracting("name", "version")
                .containsExactly(tuple("app", 1), tuple("lib", 3));
    }

    // ------------------------------------------------------------------
    // 幂等
    // ------------------------------------------------------------------

    @Test
    void sameReresolveKeyReplaysFirstReportSnapshot() {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 2)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        service.registerArtifact(requestId(), artifact("lib", 2));

        String key = reresolveKey();
        ReresolveReportResponse first = service.reresolveLock(
                key, new ReresolveRequest(lock.id(), 3L));
        // 仓库继续前进后重放同键同参：返回首次报告快照，不产生新报告。
        service.registerArtifact(requestId(), artifact("lib", 3));
        ReresolveReportResponse replay = service.reresolveLock(
                key, new ReresolveRequest(lock.id(), 3L));

        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, key)).isEqualTo(1);
    }

    @Test
    void sameReresolveKeyWithDifferentParamsReturns409() {
        service.registerArtifact(requestId(), artifact("app", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));

        String key = reresolveKey();
        service.reresolveLock(key, new ReresolveRequest(lock.id(), 1L));
        assertThatThrownBy(() -> service.reresolveLock(
                key, new ReresolveRequest(lock.id(), 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test
    void failedReresolveDoesNotConsumeKey() {
        service.registerArtifact(requestId(), artifact("app", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 1L));
        service.registerArtifact(requestId(), artifact("lib", 1));

        String key = reresolveKey();
        // 期望版本过期失败（409）不占键。
        assertThatThrownBy(() -> service.reresolveLock(
                key, new ReresolveRequest(lock.id(), 1L)))
                .isInstanceOf(ApiException.class);

        ReresolveReportResponse later = service.reresolveLock(
                key, new ReresolveRequest(lock.id(), 2L));
        assertThat(later.conclusion()).isEqualTo("REPRODUCIBLE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentSameReresolveKeyResolvesToSingleReport() throws Exception {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        String key = reresolveKey();

        List<Future<ReresolveReportResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.reresolveLock(key, new ReresolveRequest(lock.id(), 2L));
            }));
        }
        pool.shutdown();

        ReresolveReportResponse first = futures.get(0).get(30, TimeUnit.SECONDS);
        for (Future<ReresolveReportResponse> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).id()).isEqualTo(first.id());
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM idempotent_request WHERE request_id = ?",
                Integer.class, key)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 并发：重解析与撤回按提交顺序裁决，报告状态自洽
    // ------------------------------------------------------------------

    @Test
    void concurrentReresolveAndWithdrawProduceConsistentReport() throws Exception {
        service.registerArtifact(requestId(), artifact("app", 1, dep("lib", 1, 1)));
        service.registerArtifact(requestId(), artifact("lib", 1));
        LockFileResponse lock = service.createLock(requestId(), new LockRequest("app", 1, 2L));
        long versionBefore = 2L;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Object> reresolveTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return service.reresolveLock(reresolveKey(),
                        new ReresolveRequest(lock.id(), versionBefore));
            } catch (ApiException e) {
                return e;
            }
        };
        Callable<Object> withdrawTask = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.withdrawArtifact(requestId(), "lib", 1);
        };

        List<Future<Object>> futures = pool.invokeAll(List.of(reresolveTask, withdrawTask));
        pool.shutdown();
        Object reresolveOutcome = futures.get(0).get(30, TimeUnit.SECONDS);
        Object withdrawOutcome = futures.get(1).get(30, TimeUnit.SECONDS);

        assertThat(withdrawOutcome).isInstanceOf(ArtifactResponse.class);
        assertThat(repositoryVersion()).isEqualTo(versionBefore + 1);

        if (reresolveOutcome instanceof ReresolveReportResponse report) {
            // 重解析先提交：读取的是撤回前版本 2 的自洽状态，lib:1 仍有效。
            assertThat(report.repositoryVersion()).isEqualTo(versionBefore);
            assertThat(report.conclusion()).isEqualTo("REPRODUCIBLE");
            assertThat(report.entries()).extracting("name", "version")
                    .containsExactly(tuple("app", 1), tuple("lib", 1));
        } else {
            // 撤回先提交：期望版本过期，必须 409 且不产生报告。
            assertThat(reresolveOutcome).isInstanceOf(ApiException.class);
            assertThat(((ApiException) reresolveOutcome).getStatus()).isEqualTo(409);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM reresolve_report", Integer.class)).isZero();
        }
    }
}
