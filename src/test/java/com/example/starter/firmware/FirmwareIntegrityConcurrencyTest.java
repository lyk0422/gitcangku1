package com.example.starter.firmware;

import com.example.starter.firmware.api.FragmentReceiptRequest;
import com.example.starter.firmware.api.FragmentReceiptResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
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
 * 分片完整性并发边界测试（真实 H2 事务与唯一约束）：
 * 同序号并发最多一笔写入、互补分片并发收齐、接收与撤销按提交顺序裁决、
 * 失败后并发重拉只加一个代次、同 requestId 并发副作用一次。
 */
@SpringBootTest
class FirmwareIntegrityConcurrencyTest {

    private static final String D0 = FirmwareFixtures.DIGEST_0;
    private static final String D1 = FirmwareFixtures.DIGEST_1;

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        FirmwareFixtures.cleanAll(jdbc);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private long setupTask(String deviceId, String model) {
        deviceService.register(new RegisterDeviceRequest("req-" + deviceId, deviceId, model, "1.0.0", 1));
        long releaseId = releaseService.create(FirmwareFixtures.createRequest(
                "req-r-" + deviceId, model, "1.0.0", "2.0.0", 100)).releaseId();
        return taskService.pull(deviceId, "req-pull-" + deviceId).task().taskId();
    }

    private FragmentReceiptRequest fragment(String requestId, int seq, String digest) {
        return new FragmentReceiptRequest(requestId,
                List.of(new FragmentReceiptRequest.ReceivedFragment(seq, digest)));
    }

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

    @Test
    void 并发提交同序号分片_唯一约束保证最多一笔成功() throws Exception {
        long taskId = setupTask("d1", "m1");
        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receiveFragments(taskId, fragment("req-same-seq-" + seq, 0, D0)));
        }
        List<Object> results = runConcurrently(tasks);

        long ok = results.stream().filter(r -> r instanceof FragmentReceiptResponse).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && "FRAGMENT_SEQ_CONFLICT".equals(ae.code()))
                .count();
        assertThat(ok).as("同序号仅一笔写入成功").isEqualTo(1);
        assertThat(ok + conflicts).isEqualTo(threads);
        Long evidence = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND attempt = 1 AND seq_no = 0",
                Long.class, taskId);
        assertThat(evidence).isEqualTo(1);
        // 其余分片未提交，任务仍为 PENDING，不存在半成品可安装状态
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId)).isEqualTo("PENDING");
    }

    @Test
    void 并发提交互补分片_全部收齐后恰好一次可安装裁决() throws Exception {
        long taskId = setupTask("d1", "m1");
        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.receiveFragments(taskId, fragment("req-f0", 0, D0)),
                (Callable<Object>) () -> taskService.receiveFragments(taskId, fragment("req-f1", 1, D1))));
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(FragmentReceiptResponse.class));

        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId)).isEqualTo("INSTALLABLE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_integrity_event WHERE task_id = ? AND event_type = 'INSTALLABLE'",
                Long.class, taskId)).isEqualTo(1);
    }

    @Test
    void 并发接收与撤销_按提交顺序裁决且最终状态一致() throws Exception {
        int cancelWins = 0;
        int receiveWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            long taskId = setupTask(deviceId, "m-rx-" + round);
            final long lockedTaskId = taskId;
            final int seq = round;
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receiveFragments(
                            lockedTaskId, FirmwareFixtures.allFragmentsRequest("req-frag-" + seq)),
                    (Callable<Object>) () -> taskService.cancelTask(lockedTaskId, "req-cancel-" + seq)));

            Object receiveResult = results.get(0);
            TaskStatus finalStatus = TaskStatus.valueOf(
                    taskService.diagnostics(lockedTaskId).task().status());
            // 无论接收与撤销谁先提交，撤销都会终结未安装任务，终态恒为 CANCELLED
            assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
            if (receiveResult instanceof ApiException ae) {
                cancelWins++;
                // 撤销先提交：接收被唯一一致地拒绝
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
            } else {
                // 接收先提交并裁决可安装：撤销随后把可安装任务撤销
                receiveWins++;
                assertThat(receiveResult).isInstanceOf(FragmentReceiptResponse.class);
                assertThat(((FragmentReceiptResponse) receiveResult).task().status())
                        .isEqualTo(TaskStatus.INSTALLABLE.name());
            }
        }
        assertThat(cancelWins + receiveWins).isEqualTo(10);
    }

    @Test
    void 并发安装回执与撤销_可安装任务按顺序唯一裁决() throws Exception {
        int successWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            long taskId = setupTask(deviceId, "m-rc-" + round);
            final long lockedTaskId = taskId;
            final int seq = round;
            taskService.receiveFragments(lockedTaskId,
                    FirmwareFixtures.allFragmentsRequest("req-frag-" + seq));

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(lockedTaskId,
                            new ReceiptRequest("req-rc-" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> taskService.cancelTask(lockedTaskId, "req-cx-" + seq)));
            Object receiptResult = results.get(0);
            TaskStatus finalStatus = TaskStatus.valueOf(
                    taskService.diagnostics(lockedTaskId).task().status());
            if (finalStatus == TaskStatus.SUCCESS) {
                successWins++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
            } else {
                cancelWins++;
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
            }
        }
        assertThat(successWins + cancelWins).isEqualTo(10);
    }

    @Test
    void 失败后并发重新拉取_只建立一个新代次_旧证据保留() throws Exception {
        long taskId = setupTask("d1", "m1");
        taskService.receiveFragments(taskId, new FragmentReceiptRequest("req-bad", List.of(
                new FragmentReceiptRequest.ReceivedFragment(0, D0),
                new FragmentReceiptRequest.ReceivedFragment(1, "c".repeat(64)))));
        assertThat(taskService.diagnostics(taskId).task().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED.name());

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-repull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));

        assertThat(jdbc.queryForObject("SELECT attempt FROM rollout_task WHERE id = ?",
                Integer.class, taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND attempt = 1",
                Long.class, taskId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND attempt = 2",
                Long.class, taskId)).isZero();
    }

    @Test
    void 同requestId并发提交分片_副作用只发生一次且响应一致() throws Exception {
        long taskId = setupTask("d1", "m1");
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receiveFragments(taskId, fragment("req-same", 0, D0)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).as("并发同 requestId 全部重放成功").allMatch(
                r -> r instanceof FragmentReceiptResponse);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_fragment_receipt WHERE task_id = ? AND seq_no = 0",
                Long.class, taskId)).isEqualTo(1);
        for (Object result : results) {
            FragmentReceiptResponse response = (FragmentReceiptResponse) result;
            assertThat(response.receivedCount()).isEqualTo(1);
            assertThat(response.task().status()).isEqualTo(TaskStatus.PENDING.name());
        }
    }
}
