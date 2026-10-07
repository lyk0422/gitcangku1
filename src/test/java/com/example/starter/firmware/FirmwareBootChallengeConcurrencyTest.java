package com.example.starter.firmware;

import com.example.starter.firmware.api.BootMaintenanceRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.ReceiptResult;
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
 * 启动挑战并发测试：真实并发打到 H2 事务与行锁上，验证领取、回执与计数维护的串行裁决。
 */
@SpringBootTest
class FirmwareBootChallengeConcurrencyTest {

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

    private long newReleaseWithChallengeTask(String tag) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + tag, "d-" + tag, "m-" + tag, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r-" + tag, "m-" + tag, "1.0.0", "2.0.0", 100)).releaseId();
        PullResponse pull = taskService.pull("d-" + tag,
                new PullRequest("req-p-" + tag, "BOOT_CHALLENGE", "op-t"));
        jdbc.update("UPDATE rollout_task SET challenge = ? WHERE id = ?",
                "ch-" + tag, pull.task().taskId());
        return releaseId;
    }

    @Test
    void 并发挑战领取_同设备同发布单只有一条任务且挑战一致() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100))
                .releaseId();

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1",
                    new PullRequest("req-pull-" + seq, "BOOT_CHALLENGE", "op-t")));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        List<String> challenges = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
            challenges.add(response.task().challenge());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(challenges).allMatch(ch -> ch != null && ch.equals(challenges.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(1);
    }

    @Test
    void 并发相同证明回执_串行裁决只安装一次() throws Exception {
        long releaseId = newReleaseWithChallengeTask("same");
        long taskId = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE release_id = ?", Long.class, releaseId);

        int threads = 8;
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("req-c-" + seq, ReceiptResult.SUCCESS, "ch-same", "5", "op-t")));
        }
        List<Object> results = runConcurrently(tasks);

        // 回执在发布单行锁内串行：先到者安装，其余看到终态同结果返回当前视图，均不报错
        assertThat(results).noneMatch(r -> r instanceof Exception);
        // 设备计数、版本、任务与监控样本只安装一次
        assertThat(deviceService.get("d-same").bootCount()).isEqualTo(5);
        assertThat(deviceService.get("d-same").currentVersion()).isEqualTo("2.0.0");
        assertThat(taskRepository.findById(taskId).orElseThrow().status().name()).isEqualTo("SUCCESS");
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
    }

    @Test
    void 并发维护与回执_串行裁决最终状态一致() throws Exception {
        int receiptWins = 0;
        int maintainWins = 0;
        for (int round = 0; round < 10; round++) {
            String tag = "mr" + round;
            long releaseId = newReleaseWithChallengeTask(tag);
            long taskId = jdbc.queryForObject(
                    "SELECT id FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
            // 领取后维护计数到 6（档案版本 1→2）
            deviceService.maintainBootCount("d-" + tag,
                    new BootMaintenanceRequest("req-m1-" + tag, "op-t", "6", 1));

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-c-" + tag, ReceiptResult.SUCCESS, "ch-" + tag, "8", "op-t")),
                    (Callable<Object>) () -> deviceService.maintainBootCount("d-" + tag,
                            new BootMaintenanceRequest("req-m2-" + tag, "op-t", "10", 2))));

            Object receiptResult = results.get(0);
            assertThat(results.get(1)).as("维护到更高计数始终成功").isNotInstanceOf(Exception.class);
            String taskStatus = taskRepository.findById(taskId).orElseThrow().status().name();
            // 两种提交顺序都合法：回执先则 c=8>6 安装成功；维护先则 c=8 不大于 10 被拒绝
            if (receiptResult instanceof ApiException ae) {
                maintainWins++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("STALE_BOOT_COUNT");
                assertThat(taskStatus).isEqualTo("PENDING");
                assertThat(deviceService.get("d-" + tag).currentVersion()).isEqualTo("1.0.0");
                assertThat(releaseService.findOrder(releaseId).roundSuccess()).isZero();
            } else {
                receiptWins++;
                assertThat(taskStatus).isEqualTo("SUCCESS");
                assertThat(deviceService.get("d-" + tag).currentVersion()).isEqualTo("2.0.0");
                assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
            }
            // 无论顺序：最终计数为维护后的 10，档案版本只推进到 3
            assertThat(deviceService.get("d-" + tag).bootCount()).isEqualTo(10);
            assertThat(deviceService.get("d-" + tag).profileVersion()).isEqualTo(3);
        }
        assertThat(receiptWins + maintainWins).isEqualTo(10);
    }

    @Test
    void 并发同requestId挑战回执_重放一致且只安装一次() throws Exception {
        long releaseId = newReleaseWithChallengeTask("idem");
        long taskId = jdbc.queryForObject(
                "SELECT id FROM rollout_task WHERE release_id = ?", Long.class, releaseId);

        int threads = 6;
        List<Callable<TaskView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receipt(taskId,
                    new ReceiptRequest("req-same", ReceiptResult.SUCCESS, "ch-idem", "5", "op-t")));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        // 设备计数、版本与监控样本只推进一次，证明重放而非重复执行
        assertThat(deviceService.get("d-idem").bootCount()).isEqualTo(5);
        assertThat(deviceService.get("d-idem").currentVersion()).isEqualTo("2.0.0");
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isEqualTo(1);
    }
}
