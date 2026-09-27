package com.example.starter.firmware;

import com.example.starter.firmware.api.ChunkBatchRequest;
import com.example.starter.firmware.api.ChunkDigestBody;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.IntegrityView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterManifestRequest;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片完整性并发边界：真实并发打到 H2 事务、行锁与唯一约束上，验证一致提交顺序与幂等。
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
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_chunk_receipt");
        jdbc.update("DELETE FROM task_attempt");
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

    private static String digestOf(char c) {
        return String.valueOf(c).repeat(64);
    }

    private long setupTaskWithManifest(String tag, int chunkCount) {
        deviceService.register(new RegisterDeviceRequest("d-" + tag, "dev-" + tag, "m-" + tag, "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("r-" + tag, "m-" + tag, "1.0.0", "2.0.0", 100)).releaseId();
        List<ChunkDigestBody> chunks = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        for (int i = 0; i < chunkCount; i++) {
            String digest = digestOf((char) ('a' + i));
            chunks.add(new ChunkDigestBody(i, digest));
            digests.add(digest);
        }
        integrityService.registerManifest(releaseId, new RegisterManifestRequest("m-" + tag, chunks,
                IntegrityService.aggregateOf(digests)));
        return taskService.pull("dev-" + tag, "p-" + tag).task().taskId();
    }

    private long receiptCount(long taskId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_chunk_receipt WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }

    @Test
    void 并发同序号分片_最多一笔成功接收() throws Exception {
        long taskId = setupTaskWithManifest("c1", 1);

        int threads = 8;
        List<Callable<IntegrityView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> integrityService.receiveChunks(taskId,
                    new ChunkBatchRequest("cc-" + seq, List.of(new ChunkDigestBody(0, digestOf('a'))))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof IntegrityView).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        IntegrityView view = (IntegrityView) results.stream()
                .filter(r -> r instanceof IntegrityView).findFirst().orElseThrow();
        assertThat(view.status()).isEqualTo("INSTALLABLE");
        // 同任务同代次同序号最多一笔证据
        assertThat(receiptCount(taskId)).isEqualTo(1);
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.INSTALLABLE);
    }

    @Test
    void 并发重复接收_一笔接收一笔判重_证据不翻倍() throws Exception {
        long taskId = setupTaskWithManifest("c2", 2);

        List<Object> results = runConcurrently(List.of(
                (Callable<IntegrityView>) () -> integrityService.receiveChunks(taskId,
                        new ChunkBatchRequest("cc-1", List.of(new ChunkDigestBody(0, digestOf('a'))))),
                (Callable<IntegrityView>) () -> integrityService.receiveChunks(taskId,
                        new ChunkBatchRequest("cc-2", List.of(new ChunkDigestBody(0, digestOf('a')))))));

        assertThat(results).allMatch(r -> r instanceof IntegrityView);
        List<IntegrityView> views = results.stream().map(r -> (IntegrityView) r).toList();
        // 先到的一笔正常接收，后到的一笔触发重复判定
        assertThat(views.stream().map(IntegrityView::status).sorted().toList())
                .containsExactly("INTEGRITY_FAILED", "PENDING");
        assertThat(receiptCount(taskId)).isEqualTo(1);
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED);
    }

    @Test
    void 并发接收与取消_一致提交顺序且不留半成品() throws Exception {
        for (int round = 0; round < 10; round++) {
            long taskId = setupTaskWithManifest("c3-" + round, 2);
            long releaseId = jdbc.queryForObject(
                    "SELECT release_id FROM rollout_task WHERE id = ?", Long.class, taskId);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> integrityService.receiveChunks(taskId,
                            new ChunkBatchRequest("cc-" + seq, List.of(new ChunkDigestBody(0, digestOf('a'))))),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "cx-" + seq)));

            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
            // 无论谁先提交，任务最终为 CANCELLED
            assertThat(taskRepository.findById(taskId).orElseThrow().status())
                    .as("第%d轮", round).isEqualTo(TaskStatus.CANCELLED);
            Object receiveResult = results.get(0);
            if (receiveResult instanceof IntegrityView) {
                // 接收先提交：证据完整固化一笔，随后被取消
                assertThat(receiptCount(taskId)).as("第%d轮", round).isEqualTo(1);
            } else {
                // 取消先提交：接收 409，不留任何证据
                assertThat(((ApiException) receiveResult).status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(receiptCount(taskId)).as("第%d轮", round).isZero();
            }
        }
    }

    @Test
    void 并发同requestId重拉_重放一致且代次只加一次() throws Exception {
        long taskId = setupTaskWithManifest("c4", 1);
        // 先制造 INTEGRITY_FAILED
        integrityService.receiveChunks(taskId,
                new ChunkBatchRequest("bad-1", List.of(new ChunkDigestBody(0, digestOf('f')))));
        assertThat(taskRepository.findById(taskId).orElseThrow().status())
                .isEqualTo(TaskStatus.INTEGRITY_FAILED);

        int threads = 6;
        List<Callable<IntegrityView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> integrityService.repull(taskId, "repull-same"));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allMatch(r -> r instanceof IntegrityView);
        for (Object result : results) {
            assertThat(((IntegrityView) result).attemptNo()).isEqualTo(2);
        }
        Long attempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_attempt WHERE task_id = ?", Long.class, taskId);
        assertThat(attempts).isEqualTo(2);
        assertThat(taskRepository.findById(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PENDING);
    }
}
