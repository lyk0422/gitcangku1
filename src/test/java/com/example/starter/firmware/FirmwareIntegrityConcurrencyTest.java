package com.example.starter.firmware;

import com.example.starter.firmware.api.ChunkDigestInput;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ReceiveChunksRequest;
import com.example.starter.firmware.domain.ChunkEntry;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ChunkManifestRepository;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.Digests;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片完整性并发与幂等边界测试：真实并发打到 H2 事务与唯一约束上。
 */
@SpringBootTest
class FirmwareIntegrityConcurrencyTest {

    @Autowired
    private TaskService taskService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private IntegrityService integrityService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChunkReceiptRepository receiptRepository;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private ReleaseRepository releaseRepository;
    @Autowired
    private ChunkManifestRepository manifestRepository;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM integrity_event");
        jdbc.update("DELETE FROM chunk_receipt");
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM firmware_chunk");
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

    private static String dg(String seed) {
        return Digests.sha256Hex(seed);
    }

    private long setupManifestReleaseAndTask(String model, String deviceId, List<String> digests) {
        deviceService.register(
                new RegisterDeviceRequest("d-" + deviceId, deviceId, model, "1.0.0", 1));
        List<ChunkEntry> manifest = new ArrayList<>();
        for (int i = 0; i < digests.size(); i++) {
            manifest.add(new ChunkEntry(i, digests.get(i)));
        }
        String packageDigest = Digests.aggregateOf(digests);
        releaseService.create(new CreateReleaseRequest(
                "r-" + deviceId, model, "1.0.0", "2.0.0", 100, null, null,
                manifest.stream().map(e -> new ChunkDigestInput(e.chunkIndex(), e.digest())).toList(),
                packageDigest));
        return taskService.pull(deviceId, "p-" + deviceId).task().taskId();
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
    void 并发同序号分片_最多一笔成功_落选方回滚且无重复证据() throws Exception {
        List<String> digests = List.of(dg("c0"), dg("c1"));
        long taskId = setupManifestReleaseAndTask("m1", "d1", digests);

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> integrityService.receiveChunks(taskId, "cc-" + seq,
                    List.of(new ChunkDigestInput(0, digests.get(0)))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        // 唯一约束兜底：同任务同代次同序号最多一条证据
        assertThat(receiptRepository.countByTaskAndAttempt(taskId, 1)).isEqualTo(1);
        // 行锁串行后，落选方按“重复即完整性失败”裁决：任务进入 INTEGRITY_FAILED，绝不进入可安装状态
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED);
        Long failureEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INTEGRITY_FAILED'",
                Long.class, taskId);
        assertThat(failureEvents).isEqualTo(1);
        Long installableEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INSTALLABLE'",
                Long.class, taskId);
        assertThat(installableEvents).isZero();
    }

    @Test
    void 并发不同序号分片_各自成功_齐备后仅一笔可安装判定() throws Exception {
        int chunkCount = 4;
        List<String> digests = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            digests.add(dg("c" + i));
        }
        long taskId = setupManifestReleaseAndTask("m1", "d1", digests);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            int index = i;
            tasks.add(() -> integrityService.receiveChunks(taskId, "cc-" + index,
                    List.of(new ChunkDigestInput(index, digests.get(index)))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.INSTALLABLE);
        assertThat(receiptRepository.countByTaskAndAttempt(taskId, 1)).isEqualTo(chunkCount);
        Long installableEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INSTALLABLE'",
                Long.class, taskId);
        assertThat(installableEvents).isEqualTo(1);
        Long acceptedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'CHUNK_ACCEPTED'",
                Long.class, taskId);
        assertThat(acceptedEvents).isEqualTo(chunkCount);
    }

    @Test
    void 并发接收与撤销_按提交顺序裁决_终态一致且证据不被改写() throws Exception {
        List<String> digests = List.of(dg("c0"), dg("c1"));
        for (int round = 0; round < 10; round++) {
            String deviceId = "dev" + round;
            String model = "mod" + round;
            long roundTaskId = setupManifestReleaseAndTask(model, deviceId, digests);
            final int seq = round;
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> integrityService.receiveChunks(roundTaskId, "cc-" + seq,
                            List.of(new ChunkDigestInput(0, digests.get(0)),
                                    new ChunkDigestInput(1, digests.get(1)))),
                    (Callable<Object>) () -> taskService.revoke(roundTaskId, "rv-" + seq)));

            TaskStatus finalStatus = taskRepository.findById(roundTaskId).orElseThrow().status();
            Object receiveResult = results.get(0);
            int receiptCount = receiptRepository.countByTaskAndAttempt(roundTaskId, 1);
            // 接收先提交则进入 INSTALLABLE 随后被撤销置 CANCELLED；撤销先提交则接收被拒绝回滚。终态恒为 CANCELLED。
            assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
            if (receiptCount == 2) {
                // 接收先提交：2 条证据固化且不被撤销改写
                assertThat(receiveResult).isNotInstanceOf(Exception.class);
            } else {
                // 撤销先提交：接收事务回滚，无证据残留
                assertThat(receiptCount).isZero();
                assertThat(receiveResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
            }
        }
    }

    @Test
    void 完整性失败事务回滚_不留部分可安装状态_且失败不计入失败率() throws Exception {
        List<String> digests = List.of(dg("c0"), dg("c1"), dg("c2"));
        long taskId = setupManifestReleaseAndTask("m1", "d1", digests);
        long releaseId = taskRepository.findById(taskId).orElseThrow().releaseId();

        // 合法接收分片0
        integrityService.receiveChunks(taskId, "cc-ok",
                List.of(new ChunkDigestInput(0, digests.get(0))));

        // 失败批次：分片1合法但分片2摘要错误——整批回滚，分片1证据不得残留
        ApiException error = null;
        try {
            integrityService.receiveChunks(taskId, "cc-bad",
                    List.of(new ChunkDigestInput(1, digests.get(1)),
                            new ChunkDigestInput(2, dg("tampered"))));
        } catch (ApiException e) {
            error = e;
        }
        assertThat(error).isNotNull();
        assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.code()).isEqualTo("DIGEST_MISMATCH");

        // 仅有分片0的证据；任务进入 INTEGRITY_FAILED 而非 INSTALLABLE
        assertThat(receiptRepository.findByTaskAndAttempt(taskId, 1)).hasSize(1);
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED);
        // 失败事件已固化（事务提交）
        Long failureEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INTEGRITY_FAILED'",
                Long.class, taskId);
        assertThat(failureEvents).isEqualTo(1);
        // 不计入设备执行失败率，发布单仍 ACTIVE
        ReleaseOrder order = releaseRepository.findById(releaseId).orElseThrow();
        assertThat(order.roundFailed()).isZero();
        assertThat(order.status()).isEqualTo(ReleaseStatus.ACTIVE);
    }

    @Test
    void 失败不占键_重拉后同requestId修正参数可成功_旧代次保留() throws Exception {
        List<String> digests = List.of(dg("c0"), dg("c1"));
        long taskId = setupManifestReleaseAndTask("m1", "d1", digests);

        // 同 requestId 提交错误摘要：裁决为完整性失败（事务提交，证据事件落库）
        try {
            integrityService.receiveChunks(taskId, "cc-same",
                    List.of(new ChunkDigestInput(0, dg("bad"))));
            throw new IllegalStateException("预期完整性失败");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo("DIGEST_MISMATCH");
        }
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED);

        // 重拉建立新代次后，用同一 requestId 提交正确摘要成功（失败未占用幂等键）
        taskService.pull("d1", "p-repull");
        integrityService.receiveChunks(taskId, "cc-same",
                List.of(new ChunkDigestInput(0, digests.get(0)),
                        new ChunkDigestInput(1, digests.get(1))));
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INSTALLABLE);
        // 两代次：第1代 INTEGRITY_FAILED（旧失败记录保留），第2代 INSTALLABLE
        Long attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_attempt WHERE task_id = ?", Long.class, taskId);
        assertThat(attempts).isEqualTo(2);
        Long firstGenerationFailures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND attempt_no = 1"
                        + " AND event_type = 'INTEGRITY_FAILED'", Long.class, taskId);
        assertThat(firstGenerationFailures).isEqualTo(1);
        // 新代次成功后旧失败事件仍可查，不被改写
        Long totalFailures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INTEGRITY_FAILED'",
                Long.class, taskId);
        assertThat(totalFailures).isEqualTo(1);
    }

    @Test
    void 可安装后并发回执与取消_完整性已固化不受影响() throws Exception {
        List<String> digests = List.of(dg("c0"), dg("c1"));
        String deviceId = "d1";
        String model = "m1";
        deviceService.register(
                new RegisterDeviceRequest("d-x", deviceId, model, "1.0.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "r-x", model, "1.0.0", "2.0.0", 100, null, null,
                List.of(new ChunkDigestInput(0, digests.get(0)),
                        new ChunkDigestInput(1, digests.get(1))),
                Digests.aggregateOf(digests))).releaseId();
        long taskId = taskService.pull(deviceId, "p-x").task().taskId();
        integrityService.receiveChunks(taskId, "cc-x",
                List.of(new ChunkDigestInput(0, digests.get(0)),
                        new ChunkDigestInput(1, digests.get(1))));
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INSTALLABLE);

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.receipt(taskId,
                        new ReceiptRequest("rc-x", ReceiptResult.SUCCESS)),
                (Callable<Object>) () -> releaseService.cancel(releaseId, "cx-x")));

        TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
        Object receiptResult = results.get(0);
        if (finalStatus == TaskStatus.SUCCESS) {
            assertThat(receiptResult).isNotInstanceOf(Exception.class);
        } else {
            assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
            assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                    ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
        }
        // 无论谁先提交，已固化的接收证据与判定事件数量不变
        assertThat(receiptRepository.countByTaskAndAttempt(taskId, 1)).isEqualTo(2);
        Long installableEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integrity_event WHERE task_id = ? AND event_type = 'INSTALLABLE'",
                Long.class, taskId);
        assertThat(installableEvents).isEqualTo(1);
    }
}
