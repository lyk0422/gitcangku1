package com.example.starter.firmware;

import com.example.starter.firmware.api.ChunkDigestEntry;
import com.example.starter.firmware.api.ChunkSubmissionView;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterManifestRequest;
import com.example.starter.firmware.api.SubmitChunksRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.IntegrityRecordRepository;
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
 * 分片完整性并发边界测试：真实并发打到 H2 事务、行锁与唯一约束上，
 * 验证分片接收、重新拉取、取消与安装回执按提交顺序裁决。
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
    private ChunkReceiptRepository chunkReceiptRepository;

    @Autowired
    private IntegrityRecordRepository integrityRecordRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_chunk_receipt");
        jdbc.update("DELETE FROM task_integrity_record");
        jdbc.update("DELETE FROM release_manifest_chunk");
        jdbc.update("DELETE FROM release_manifest");
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

    private static String digest(int seed) {
        return String.format("%064x", seed);
    }

    private long newReleaseWithManifest(String tag, String model, List<String> digests) {
        deviceService.register(new RegisterDeviceRequest("req-d" + tag, "d" + tag, model, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r" + tag, model, "1.0.0", "2.0.0", 100)).releaseId();
        List<ChunkDigestEntry> chunks = new ArrayList<>();
        for (int i = 0; i < digests.size(); i++) {
            chunks.add(new ChunkDigestEntry(i, digests.get(i)));
        }
        integrityService.registerManifest(releaseId, new RegisterManifestRequest(
                "req-m" + tag, Digests.aggregate(digests), chunks));
        return releaseId;
    }

    @Test
    void 并发同序号分片_最多一笔成功() throws Exception {
        long releaseId = newReleaseWithManifest("a", "m1", List.of(digest(1), digest(2), digest(3)));
        long taskId = taskService.pull("da", "req-p").task().taskId();

        int threads = 6;
        List<Callable<ChunkSubmissionView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> integrityService.submitChunks(taskId, new SubmitChunksRequest(
                    "req-c" + seq, List.of(new ChunkDigestEntry(0, digest(1))), false)));
        }
        List<Object> results = runConcurrently(tasks);

        long accepted = results.stream()
                .filter(r -> r instanceof ChunkSubmissionView view && view.acceptedCount() == 1)
                .count();
        assertThat(accepted).isEqualTo(1);
        // 唯一约束兜底：同任务同代次同序号至多一条证据
        assertThat(chunkReceiptRepository.countByTaskAndAttemptAndIndex(taskId, 1, 0)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ?", Long.class, taskId))
                .isEqualTo(1);
    }

    @Test
    void 并发重新拉取_代次只加一次且旧证据保留() throws Exception {
        long releaseId = newReleaseWithManifest("b", "m1", List.of(digest(1)));
        long taskId = taskService.pull("db", "req-p").task().taskId();
        // 制造 INTEGRITY_FAILED：提交错误摘要
        ChunkSubmissionView failed = integrityService.submitChunks(taskId, new SubmitChunksRequest(
                "req-bad", List.of(new ChunkDigestEntry(0, digest(999))), false));
        assertThat(failed.status()).isEqualTo(TaskStatus.INTEGRITY_FAILED.name());

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("db", "req-rp" + seq));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        var task = taskRepository.findById(taskId).orElseThrow();
        assertThat(task.attempt()).isEqualTo(2);
        assertThat(task.status()).isEqualTo(TaskStatus.PENDING);
        // 旧代次证据与判定记录保留
        assertThat(chunkReceiptRepository.countByTaskAndAttemptAndIndex(taskId, 1, 0)).isEqualTo(1);
        assertThat(integrityRecordRepository.findByTask(taskId)).hasSize(1);
    }

    @Test
    void 并发分片接收与取消_一致提交顺序且证据不改写() throws Exception {
        int installableThenCancelled = 0;
        int cancelledBeforeSubmit = 0;
        for (int round = 0; round < 8; round++) {
            String tag = "c" + round;
            long releaseId = newReleaseWithManifest(tag, "m" + tag, List.of(digest(1)));
            long taskId = taskService.pull("d" + tag, "req-p" + round).task().taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> integrityService.submitChunks(taskId, new SubmitChunksRequest(
                            "req-s" + seq, List.of(new ChunkDigestEntry(0, digest(1))), false)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));

            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.CANCELLED);
            var task = taskRepository.findById(taskId).orElseThrow();
            assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
            Object submission = results.get(0);
            if (submission instanceof ChunkSubmissionView view) {
                // 接收先提交：可安装判定与证据已固化，取消不改写
                installableThenCancelled++;
                assertThat(view.status()).isEqualTo(TaskStatus.INSTALLABLE.name());
                assertThat(chunkReceiptRepository.countByTaskAndAttemptAndIndex(taskId, 1, 0)).isEqualTo(1);
                assertThat(integrityRecordRepository.findByTask(taskId)).hasSize(1);
            } else {
                // 取消先提交：后到接收 409，无任何分片证据
                cancelledBeforeSubmit++;
                assertThat(submission).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(chunkReceiptRepository.countByTaskAndAttemptAndIndex(taskId, 1, 0)).isZero();
                assertThat(integrityRecordRepository.findByTask(taskId)).isEmpty();
            }
        }
        assertThat(installableThenCancelled + cancelledBeforeSubmit).isEqualTo(8);
    }

    @Test
    void 并发末片提交与成功回执_按提交顺序裁决唯一结果() throws Exception {
        int receiptFirst = 0;
        int submitFirst = 0;
        for (int round = 0; round < 8; round++) {
            String tag = "e" + round;
            long releaseId = newReleaseWithManifest(tag, "m" + tag, List.of(digest(1), digest(2)));
            long taskId = taskService.pull("d" + tag, "req-p" + round).task().taskId();
            integrityService.submitChunks(taskId, new SubmitChunksRequest(
                    "req-s0-" + round, List.of(new ChunkDigestEntry(0, digest(1))), false));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> integrityService.submitChunks(taskId, new SubmitChunksRequest(
                            "req-s1-" + seq, List.of(new ChunkDigestEntry(1, digest(2))), false)),
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-rc-" + seq, ReceiptResult.SUCCESS))));

            Object receipt = results.get(1);
            var task = taskRepository.findById(taskId).orElseThrow();
            if (receipt instanceof ApiException ae) {
                // 回执先裁决：任务未核验禁止成功回执，随后末片提交使其可安装
                receiptFirst++;
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("TASK_NOT_INSTALLABLE");
                assertThat(task.status()).isEqualTo(TaskStatus.INSTALLABLE);
                assertThat(deviceService.get("d" + tag).currentVersion()).isEqualTo("1.0.0");
            } else {
                // 末片先提交：任务可安装后回执成功
                submitFirst++;
                assertThat(task.status()).isEqualTo(TaskStatus.SUCCESS);
                assertThat(deviceService.get("d" + tag).currentVersion()).isEqualTo("2.0.0");
            }
            assertThat(integrityRecordRepository.findByTask(taskId)).hasSize(1);
        }
        assertThat(receiptFirst + submitFirst).isEqualTo(8);
    }
}
