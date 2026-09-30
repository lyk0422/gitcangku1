package com.example.starter.firmware;

import com.example.starter.firmware.api.ApproveRestoreRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.FreezeVersionRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RestoreFreezeRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.FreezeService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结与任务创建竞争、并发重复冻结、冻结下并发拉取与恢复审批测试（真实 H2 事务、行锁与唯一约束）。
 */
@SpringBootTest
class FirmwareFreezeConcurrencyTest {

    @Autowired
    private FreezeService freezeService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM version_freeze_restore");
        jdbc.update("DELETE FROM version_freeze");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        jdbc.update("DELETE FROM version_freeze_restore");
        jdbc.update("DELETE FROM version_freeze");
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    private static boolean isConflict(Exception e, String code) {
        return e instanceof ApiException ae
                && ae.status() == HttpStatus.CONFLICT
                && ae.code().equals(code);
    }

    @Test
    void 冻结与拉取竞争_按提交顺序决定是否可建新任务() throws Exception {
        int freezeWins = 0;
        int pullWins = 0;
        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            String model = "m" + round;
            String deviceId = "d" + round;
            String version = "2.0." + round;
            releaseService.create(new CreateReleaseRequest("rel-" + round, model, "1.0.0", version, 100));
            jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?, ?, '1.0.0', 1)",
                    deviceId, model);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> freezeService.freeze(
                            new FreezeVersionRequest("fz-" + seq, version, "jia", "紧急冻结")),
                    (Callable<Object>) () -> taskService.pull(deviceId, "pull-" + seq)));

            Object pullResult = results.get(1);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task rt JOIN release_order ro ON rt.release_id = ro.id"
                            + " WHERE ro.to_version = ?", Long.class, version);
            Long freezeCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM version_freeze WHERE version_key = ?", Long.class, version);
            assertThat(freezeCount).as("每轮恰有一条冻结（第%d轮）", round).isEqualTo(1);

            if (pullResult instanceof Exception e && isConflict(e, "RELEASE_VERSION_FROZEN")) {
                // 冻结先提交：拉取 409，任务未创建（分桶容量未消耗）
                freezeWins++;
                assertThat(taskCount).as("冻结先提交时不得建任务（第%d轮）", round).isZero();
            } else {
                // 拉取先提交：任务已落库，冻结随后生效
                pullWins++;
                assertThat(pullResult).isInstanceOf(PullResponse.class);
                PullResponse response = (PullResponse) pullResult;
                assertThat(response.task()).isNotNull();
                assertThat(taskCount).as("拉取先提交时恰有一条任务（第%d轮）", round).isEqualTo(1);
            }
        }
        assertThat(freezeWins + pullWins).isEqualTo(rounds);
    }

    @Test
    void 并发重复冻结_恰有一条生效其余409() throws Exception {
        releaseService.create(new CreateReleaseRequest("rel", "m1", "1.0.0", "2.0.0", 100));
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> freezeService.freeze(
                    new FreezeVersionRequest("fz-" + seq, "2.0.0", "jia", "原因" + seq)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof Exception e && isConflict(e, "VERSION_FROZEN"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM version_freeze WHERE version_key = '2.0.0'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 冻结生效后并发拉取_全部409且零任务_旧任务回执不受影响() throws Exception {
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO device (device_id, model, current_version, bucket_no) VALUES (?, 'm1', '1.0.0', ?)",
                    "d" + i, i);
        }
        long releaseId = releaseService.create(
                new CreateReleaseRequest("rel", "m1", "1.0.0", "2.0.0", 100)).releaseId();
        // 先建一条旧任务
        var oldPull = taskService.pull("d0", "pull-old");
        assertThat(oldPull.task()).isNotNull();
        long oldTaskId = oldPull.task().taskId();

        freezeService.freeze(new FreezeVersionRequest("fz", "2.0.0", "jia", "冻结"));

        // 3 个新设备并发拉取：全部 409，零新任务
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String deviceId = "d" + i;
            tasks.add(() -> taskService.pull(deviceId, "pull-" + deviceId));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isInstanceOfSatisfying(Exception.class,
                    e -> assertThat(isConflict(e, "RELEASE_VERSION_FROZEN")).isTrue());
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId))
                .isEqualTo(1);

        // 同一新设备用同一 requestId 并发拉取：同样全部 409，仍零新任务（409 不占键）
        List<Callable<Object>> sameKey = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sameKey.add(() -> taskService.pull("d1", "same-pull"));
        }
        List<Object> sameResults = runConcurrently(sameKey);
        for (Object result : sameResults) {
            assertThat(result).isInstanceOfSatisfying(Exception.class,
                    e -> assertThat(isConflict(e, "RELEASE_VERSION_FROZEN")).isTrue());
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId))
                .isEqualTo(1);
    }

    @Test
    void 并发同人审批全部422冻结不变_异人审批仅一次生效() throws Exception {
        freezeService.freeze(new FreezeVersionRequest("fz", "2.0.0", "jia", "冻结"));
        long restoreId = freezeService.requestRestore("2.0.0",
                new RestoreFreezeRequest("req", "jia", "恢复申请")).restoreId();

        // 冻结人并发自批：全部 422，冻结保持 FROZEN，申请保持 PENDING
        List<Callable<Object>> selfApprovals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            selfApprovals.add(() -> freezeService.approve("2.0.0", restoreId,
                    new ApproveRestoreRequest("self-" + seq, "jia")));
        }
        List<Object> selfResults = runConcurrently(selfApprovals);
        for (Object result : selfResults) {
            assertThat(result).isInstanceOfSatisfying(ApiException.class,
                    ae -> {
                        assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ae.code()).isEqualTo("RESTORE_APPROVER_CONFLICT");
                    });
        }
        assertThat(jdbc.queryForObject(
                "SELECT status FROM version_freeze WHERE version_key = '2.0.0'", String.class))
                .isEqualTo("FROZEN");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM version_freeze_restore WHERE id = ?", String.class, restoreId))
                .isEqualTo("PENDING");

        // 异人并发审批：恰有一次生效，冻结解除，申请仅一行 APPROVED
        List<Callable<Object>> approvals = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            approvals.add(() -> freezeService.approve("2.0.0", restoreId,
                    new ApproveRestoreRequest("ok-" + seq, "yi")));
        }
        List<Object> okResults = runConcurrently(approvals);
        long successes = okResults.stream().filter(r -> !(r instanceof Exception)).count();
        assertThat(successes).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM version_freeze WHERE version_key = '2.0.0'", String.class))
                .isEqualTo("RESUMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM version_freeze_restore WHERE id = ? AND status = 'APPROVED'",
                Long.class, restoreId)).isEqualTo(1);
    }
}
