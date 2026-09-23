package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.api.RetryTaskRequest;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
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
 * 重试并发边界测试：真实并发打到 H2 事务与唯一约束上，
 * 验证同一失败任务至多一个后继、重试与暂停/恢复/取消按提交顺序裁决。
 */
@SpringBootTest
class FirmwareRetryConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
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
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常。
     */
    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
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

    private long failedTask(String deviceId, String model, int seq) {
        deviceService.register(new RegisterDeviceRequest("req-d" + seq, deviceId, model, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r" + seq, model, "1.0.0", "2.0.0", 100, 100, 100))
                .releaseId();
        long taskId = taskService.pull(deviceId, "req-p" + seq).task().taskId();
        taskService.receipt(taskId, new ReceiptRequest("req-f" + seq, ReceiptResult.FAILED));
        return taskId;
    }

    @Test
    void 并发重试_同一失败任务只创建一个后继() throws Exception {
        long t1 = failedTask("d1", "m1", 0);
        long releaseId = jdbc.queryForObject("SELECT release_id FROM rollout_task WHERE id = ?",
                Long.class, t1);

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.retry(t1, new RetryTaskRequest("req-retry-" + seq, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof TaskView).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        // 只追加一条后继：序号2，前驱为原任务
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(2);
        var latest = taskRepository.findLatestByReleaseAndDevice(releaseId, "d1").orElseThrow();
        assertThat(latest.attemptNo()).isEqualTo(2);
        assertThat(latest.predecessorId()).isEqualTo(t1);
        assertThat(latest.status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void 并发重试与取消_最终状态一致且无遗留PENDING() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            long t1 = failedTask(deviceId, model, round);
            long releaseId = jdbc.queryForObject("SELECT release_id FROM rollout_task WHERE id = ?",
                    Long.class, t1);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.retry(t1, new RetryTaskRequest("req-rt" + seq, 1)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-cx" + seq)));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            // 取消始终成功；重试要么 409（取消先提交），要么先创建随后被一并取消
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.CANCELLED);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                    Long.class, releaseId);
            assertThat(pending).as("取消后不得残留 PENDING 任务（第%d轮）", round).isZero();
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
            if (results.get(0) instanceof TaskView) {
                assertThat(total).as("重试先提交：后继已创建并被取消（第%d轮）", round).isEqualTo(2);
            } else {
                assertThat(((ApiException) results.get(0)).status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(total).as("取消先提交：无后继（第%d轮）", round).isEqualTo(1);
            }
        }
    }

    @Test
    void 并发重试与自动暂停_按提交顺序裁决() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d1-" + round, "a" + round, model, "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("req-d2-" + round, "b" + round, model, "1.0.0", 2));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100, 2, 50))
                    .releaseId();
            long t1 = taskService.pull("a" + round, "req-p1-" + round).task().taskId();
            long t2 = taskService.pull("b" + round, "req-p2-" + round).task().taskId();
            taskService.receipt(t1, new ReceiptRequest("req-f1-" + round, ReceiptResult.FAILED));
            final int seq = round;

            // 重试（需 ACTIVE）与触发自动暂停的失败回执竞争
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.retry(t1, new RetryTaskRequest("req-rt" + seq, 1)),
                    (Callable<Object>) () -> taskService.receipt(t2,
                            new ReceiptRequest("req-f2-" + seq, ReceiptResult.FAILED))));
            assertThat(results.get(1)).as("暂停侧回执始终成功").isNotInstanceOf(Exception.class);
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.PAUSED);

            Long retryPending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND attempt_no = 2"
                            + " AND status = 'PENDING'",
                    Long.class, releaseId);
            if (results.get(0) instanceof TaskView) {
                // 重试先提交：后继已创建，暂停不取消已有 PENDING
                assertThat(retryPending).isEqualTo(1);
            } else {
                // 暂停先提交：重试 409 RELEASE_NOT_ACTIVE
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("RELEASE_NOT_ACTIVE");
                        });
                assertThat(retryPending).isZero();
            }
        }
    }

    @Test
    void 并发重试与恢复_按提交顺序裁决() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d1-" + round, "a" + round, model, "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("req-d2-" + round, "b" + round, model, "1.0.0", 2));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", "2.0.0", 100, 2, 50))
                    .releaseId();
            long t1 = taskService.pull("a" + round, "req-p1-" + round).task().taskId();
            long t2 = taskService.pull("b" + round, "req-p2-" + round).task().taskId();
            taskService.receipt(t1, new ReceiptRequest("req-f1-" + round, ReceiptResult.FAILED));
            taskService.receipt(t2, new ReceiptRequest("req-f2-" + round, ReceiptResult.FAILED));
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.PAUSED);
            final int seq = round;

            // 重试（期望恢复后版本2）与恢复竞争：重试先提交则 409，恢复先提交则成功
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.retry(t1, new RetryTaskRequest("req-rt" + seq, 2)),
                    (Callable<Object>) () -> releaseService.resume(releaseId,
                            new ResumeReleaseRequest("req-res" + seq, 1, "修复完成"))));
            assertThat(results.get(1)).as("恢复对 PAUSED 发布单始终成功").isNotInstanceOf(Exception.class);
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.ACTIVE);
            assertThat(releaseService.findOrder(releaseId).version()).isEqualTo(2);

            Long retryCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND attempt_no = 2",
                    Long.class, releaseId);
            if (results.get(0) instanceof TaskView view) {
                // 恢复先提交：重试在新版本下成功
                assertThat(view.attemptNo()).isEqualTo(2);
                assertThat(retryCount).isEqualTo(1);
            } else {
                // 重试先提交：发布单仍 PAUSED，409
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> {
                            assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(ae.code()).isEqualTo("RELEASE_NOT_ACTIVE");
                        });
                assertThat(retryCount).isZero();
            }
        }
    }
}
