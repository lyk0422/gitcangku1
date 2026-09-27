package com.example.starter.firmware;

import com.example.starter.firmware.api.BatchShardReceiptRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ShardReceiptRequest;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.DigestRules;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
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
 * 分片接收并发与幂等边界测试：真实并发打到 H2 行锁、任务行锁与唯一约束上，
 * 验证按提交顺序裁决：同任务同代次同序号最多一笔成功；接收与撤销/回执之间不留半成品。
 */
@SpringBootTest
class FirmwareShardConcurrencyTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    private static String d(String content) {
        return DigestRules.sha256Hex(content);
    }

    private static String pkg(String... contents) {
        return DigestRules.aggregateOfOrderedDigests(java.util.Arrays.stream(contents)
                .map(DigestRules::sha256Hex).toList());
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_shard_receipt");
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_shard");
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

    private long setupTwoShardRelease(String deviceId, String model, int bucket) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + deviceId, deviceId, model, "1.0.0", bucket));
        var shards = List.of(
                new com.example.starter.firmware.api.ShardInput(0, d("s0")),
                new com.example.starter.firmware.api.ShardInput(1, d("s1")));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r-" + deviceId, model,
                "1.0.0", "2.0.0", 100, 2, 50, pkg("s0", "s1"), shards)).releaseId();
        return releaseId;
    }

    private long pull(String deviceId) {
        PullResponse response = taskService.pull(deviceId, "req-pull-" + deviceId);
        assertThat(response.task()).isNotNull();
        return response.task().taskId();
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
    void 并发同序号分片_最多一笔成功_证据唯一_输家409() throws Exception {
        long releaseId = setupTwoShardRelease("d1", "m1", 1);
        long taskId = pull("d1");

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receiveShard(taskId,
                    new ShardReceiptRequest("req-shard-" + seq, 0, d("s0"))));
        }
        List<Object> results = runConcurrently(tasks);

        long winners = results.stream().filter(r -> r instanceof TaskView).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && "SHARD_ALREADY_RECEIVED".equals(ae.code()))
                .count();
        assertThat(winners).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);

        Long evidence = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_shard_receipt WHERE task_id = ? AND attempt_no = 1 AND shard_index = 0",
                Long.class, taskId);
        assertThat(evidence).isEqualTo(1);
        // 仅收到序号0，缺口1片：仍为接收中，未被并发拖成失败
        TaskView view = taskService.diagnostics(taskId).task();
        assertThat(view.status()).isEqualTo(TaskStatus.PENDING.name());
        assertThat(view.receivedShards()).isEqualTo(1);
        assertThat(view.missingShards()).isEqualTo(1);
        assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.ACTIVE);
    }

    @Test
    void 并发不同序号分片_全部成功且恰好可安装() throws Exception {
        setupTwoShardRelease("d1", "m1", 1);
        long taskId = pull("d1");

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.receiveShard(taskId,
                        new ShardReceiptRequest("req-a", 0, d("s0"))),
                (Callable<Object>) () -> taskService.receiveShard(taskId,
                        new ShardReceiptRequest("req-b", 1, d("s1")))));

        assertThat(results).allMatch(r -> r instanceof TaskView);
        TaskView view = taskService.diagnostics(taskId).task();
        assertThat(view.status()).isEqualTo(TaskStatus.INSTALLABLE.name());
        assertThat(view.receivedShards()).isEqualTo(2);
        Long evidence = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_shard_receipt WHERE task_id = ? AND attempt_no = 1",
                Long.class, taskId);
        assertThat(evidence).isEqualTo(2);
    }

    @Test
    void 并发接收与撤销_按提交顺序唯一裁决_无半成品() throws Exception {
        int cancelledFirst = 0;
        int receivedFirst = 0;
        for (int round = 0; round < 8; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            setupTwoShardRelease(deviceId, model, round);
            long taskId = pull(deviceId);
            String rid = "req-race-" + round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receiveShardsBatch(taskId,
                            new BatchShardReceiptRequest(rid + "-b", List.of(
                                    new BatchShardReceiptRequest.ShardReceiptItem(0, d("s0")),
                                    new BatchShardReceiptRequest.ShardReceiptItem(1, d("s1"))))),
                    (Callable<Object>) () -> taskService.cancel(taskId, rid + "-c")));

            // 裁决后发布单取消总是成功，最终任务为 CANCELLED；按接收方是否先提交区分两类结果
            TaskStatus status = TaskStatus.valueOf(taskService.diagnostics(taskId).task().status());
            assertThat(status).isEqualTo(TaskStatus.CANCELLED);
            assertThat(results.get(1)).as("撤销调用本身始终成功").isNotInstanceOf(Exception.class);
            Object receiveResult = results.get(0);
            Long evidence = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task_shard_receipt WHERE task_id = ?", Long.class, taskId);
            if (receiveResult instanceof TaskView view) {
                // 接收先提交：响应时刻为 INSTALLABLE 且两片证据固化；随后的撤销只改状态、不删旧证据
                receivedFirst++;
                assertThat(view.status()).isEqualTo(TaskStatus.INSTALLABLE.name());
                assertThat(evidence).isEqualTo(2);
            } else {
                // 撤销先提交：接收 409 整笔回滚，无证据残留，不留半成品
                cancelledFirst++;
                assertThat(receiveResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(evidence).isZero();
            }
        }
        assertThat(cancelledFirst + receivedFirst).isEqualTo(8);
    }

    @Test
    void 并发同requestId批量接收_重放一致且证据只插一次() throws Exception {
        setupTwoShardRelease("d1", "m1", 1);
        long taskId = pull("d1");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.receiveShardsBatch(taskId,
                    new BatchShardReceiptRequest("req-same", List.of(
                            new BatchShardReceiptRequest.ShardReceiptItem(0, d("s0")),
                            new BatchShardReceiptRequest.ShardReceiptItem(1, d("s1"))))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).allMatch(r -> r instanceof TaskView);

        TaskView view = taskService.diagnostics(taskId).task();
        assertThat(view.status()).isEqualTo(TaskStatus.INSTALLABLE.name());
        assertThat(view.attemptNo()).isEqualTo(1);
        Long evidence = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_shard_receipt WHERE task_id = ? AND attempt_no = 1",
                Long.class, taskId);
        assertThat(evidence).isEqualTo(2);
        // 成功结果占用 requestId：异参重放 409
        try {
            taskService.receiveShard(taskId, new ShardReceiptRequest("req-same", 0, d("s0")));
            throw new AssertionError("期望 REQUEST_ID_CONFLICT");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("REQUEST_ID_CONFLICT");
        }
    }

    @Test
    void 并发完整性失败_不占requestId且不影响其他任务失败率() throws Exception {
        // 两台设备；d1 多线程并发提交错误的序号0，只有一个事务能落证据并判失败
        setupTwoShardRelease("d1", "m1", 1);
        deviceService.register(new RegisterDeviceRequest("req-d-d2", "d2", "m1", "1.0.0", 2));
        long releaseId = releaseService.findOrder(
                jdbc.queryForObject("SELECT id FROM release_order", Long.class)).id();
        long t1 = pull("d1");

        int threads = 4;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.receiveShard(t1,
                    new ShardReceiptRequest("req-bad-" + seq, 0, d("wrong"))));
        }
        List<Object> results = runConcurrently(tasks);
        long failed = results.stream().filter(r -> r instanceof TaskView v
                && TaskStatus.INTEGRITY_FAILED.name().equals(v.status())).count();
        long rejected = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(failed).isEqualTo(1);
        assertThat(failed + rejected).isEqualTo(threads);

        // 失败不占键：其中一个 requestId 换参数仍可用于新任务 d2
        long t2 = taskService.pull("d2", "req-pull-d2").task().taskId();
        TaskView reused = taskService.receiveShard(t2,
                new ShardReceiptRequest("req-bad-0", 0, d("s0")));
        assertThat(reused.status()).isEqualTo(TaskStatus.PENDING.name());

        // 完整性失败不计入失败率：roundFailed 始终 0
        ReleaseOrder order = releaseService.findOrder(releaseId);
        assertThat(order.roundFailed()).isZero();
        assertThat(order.roundSuccess()).isZero();
        assertThat(order.status()).isEqualTo(ReleaseStatus.ACTIVE);
    }
}
