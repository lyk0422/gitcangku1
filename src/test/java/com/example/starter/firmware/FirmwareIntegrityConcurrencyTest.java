package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiveShardsRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterShardsRequest;
import com.example.starter.firmware.api.ShardReceiveResponse;
import com.example.starter.firmware.domain.Digests;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.IntegrityEventRepository;
import com.example.starter.firmware.repo.ShardReceiptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.IntegrityService;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片完整性并发边界测试：真实并发打到 H2 事务、行锁与唯一约束上，
 * 验证同序号分片至多一笔成功、补全判定唯一、取消与重拉的一致提交顺序。
 */
@SpringBootTest
class FirmwareIntegrityConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private IntegrityService integrityService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ShardReceiptRepository shardReceiptRepository;

    @Autowired
    private IntegrityEventRepository integrityEventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM shard_receipt");
        jdbc.update("DELETE FROM integrity_event");
        jdbc.update("DELETE FROM release_shard");
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

    private static String digestOf(int i) {
        return Digests.aggregateHex(List.of("shard-seed-" + i));
    }

    private static String fullDigestOf(int shardCount) {
        return Digests.aggregateHex(IntStream.range(0, shardCount)
                .mapToObj(FirmwareIntegrityConcurrencyTest::digestOf).toList());
    }

    private static RegisterShardsRequest manifestRequest(String requestId, int shardCount) {
        List<RegisterShardsRequest.ShardDigestInput> shards = IntStream.range(0, shardCount)
                .mapToObj(i -> new RegisterShardsRequest.ShardDigestInput(i, digestOf(i)))
                .toList();
        return new RegisterShardsRequest(requestId, fullDigestOf(shardCount), shards);
    }

    private long prepareTask(String deviceId, int shardCount) {
        deviceService.register(new RegisterDeviceRequest("req-d-" + deviceId, deviceId, "m1", "1.0.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "req-r-" + deviceId, "m1", "1.0.0", "2.0.0", 100)).releaseId();
        integrityService.registerShards(releaseId, manifestRequest("req-m-" + deviceId, shardCount));
        return taskService.pull(deviceId, "req-p-" + deviceId).task().taskId();
    }

    @Test
    void 并发同序号分片_最多一笔成功_另一笔判重复失败() throws Exception {
        long taskId = prepareTask("d1", 2);

        List<Callable<ShardReceiveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> integrityService.receiveShards(taskId, new ReceiveShardsRequest(
                    "req-s" + seq, List.of(new ReceiveShardsRequest.ReceivedShard(0, digestOf(0))))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allMatch(r -> r instanceof ShardReceiveResponse);
        List<String> statuses = results.stream()
                .map(r -> ((ShardReceiveResponse) r).status()).sorted().toList();
        // 一笔成功接收（PENDING 继续），另一笔因重复判定 INTEGRITY_FAILED
        assertThat(statuses).containsExactly("INTEGRITY_FAILED", "PENDING");
        // 同序号分片至多一笔落证据
        assertThat(shardReceiptRepository.findByTaskAndAttempt(taskId, 1)).hasSize(1);
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED);
        assertThat(integrityEventRepository.findByTask(taskId)).hasSize(1)
                .allMatch(e -> e.reason().equals("DUPLICATE_SHARD"));
    }

    @Test
    void 并发不同序号分片_补全后恰好一次可安装判定() throws Exception {
        int shardCount = 4;
        long taskId = prepareTask("d1", shardCount);

        List<Callable<ShardReceiveResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            int shardNo = i;
            tasks.add(() -> integrityService.receiveShards(taskId, new ReceiveShardsRequest(
                    "req-s" + shardNo,
                    List.of(new ReceiveShardsRequest.ReceivedShard(shardNo, digestOf(shardNo))))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allMatch(r -> r instanceof ShardReceiveResponse);
        long installable = results.stream()
                .filter(r -> ((ShardReceiveResponse) r).status().equals("INSTALLABLE")).count();
        // 任务行锁串行裁决：恰好一笔提交观察到完整集合并判定可安装
        assertThat(installable).isEqualTo(1);
        assertThat(shardReceiptRepository.findByTaskAndAttempt(taskId, 1)).hasSize(shardCount);
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INSTALLABLE);
        assertThat(taskRepository.findById(taskId).orElseThrow().aggregateDigest())
                .isEqualTo(fullDigestOf(shardCount));
        assertThat(integrityEventRepository.findByTask(taskId)).hasSize(1)
                .allMatch(e -> e.result().equals("INSTALLABLE"));
    }

    @Test
    void 并发分片提交与取消_一致提交顺序() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    "req-r" + round, model, "1.0.0", "2.0.0", 100)).releaseId();
            integrityService.registerShards(releaseId, manifestRequest("req-m" + round, 2));
            long taskId = taskService.pull(deviceId, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> integrityService.receiveShards(taskId,
                            new ReceiveShardsRequest("req-s" + seq, List.of(
                                    new ReceiveShardsRequest.ReceivedShard(0, digestOf(0)),
                                    new ReceiveShardsRequest.ReceivedShard(1, digestOf(1))))),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));

            Object submit = results.get(0);
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            // 无论谁先提交，任务最终都被取消；分片提交要么先完成（INSTALLABLE后被取消），
            // 要么因任务已取消而 409，不得出现半成品状态
            assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
            if (submit instanceof ApiException ae) {
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("TASK_CANCELLED");
                assertThat(shardReceiptRepository.findByTask(taskId)).isEmpty();
            } else {
                assertThat(((ShardReceiveResponse) submit).status()).isEqualTo("INSTALLABLE");
                assertThat(shardReceiptRepository.findByTask(taskId)).hasSize(2);
            }
        }
    }

    @Test
    void 并发重新拉取_只建立一个新尝试代次() throws Exception {
        long taskId = prepareTask("d1", 2);
        // 第一代次完整性失败
        ShardReceiveResponse failed = integrityService.receiveShards(taskId, new ReceiveShardsRequest(
                "req-bad", List.of(new ReceiveShardsRequest.ReceivedShard(0,
                        Digests.aggregateHex(List.of("tampered"))))));
        assertThat(failed.status()).isEqualTo("INTEGRITY_FAILED");

        int threads = 8;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-rp" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).noneMatch(r -> r instanceof Exception);
        // 只建立一个新代次：第2代任务唯一，全部并发拉取返回同一任务
        List<Long> attempt2Ids = jdbc.queryForList(
                        "SELECT id FROM rollout_task WHERE device_id = 'd1' AND attempt_no = 2", Long.class);
        assertThat(attempt2Ids).hasSize(1);
        assertThat(taskRepository.countByReleaseAndDevice(
                taskRepository.findById(taskId).orElseThrow().releaseId(), "d1")).isEqualTo(2);
        // 旧代次证据保留
        assertThat(shardReceiptRepository.findByTaskAndAttempt(taskId, 1)).hasSize(1);
    }
}
