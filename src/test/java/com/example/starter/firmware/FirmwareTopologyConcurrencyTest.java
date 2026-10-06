package com.example.starter.firmware;

import com.example.starter.firmware.api.ClaimWaveRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RecoverDeviceRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.TopologyEdgeRequest;
import com.example.starter.firmware.api.TopologyRequest;
import com.example.starter.firmware.api.WaveView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import com.example.starter.firmware.service.TopologyWaveService;
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
 * 拓扑波次并发测试：真实并发打到 H2 事务与唯一约束上，
 * 验证领波次串行化、幂等重放单次副作用、人工恢复版本只加一次、回执与取消的一致提交顺序。
 */
@SpringBootTest
class FirmwareTopologyConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TopologyWaveService topologyWaveService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM release_wave_member");
        jdbc.update("DELETE FROM release_wave");
        jdbc.update("DELETE FROM release_topology_edge");
        jdbc.update("DELETE FROM release_topology_node");
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

    private long createTopologyRelease(String requestId, List<String> deviceIds,
                                       List<TopologyEdgeRequest> edges) {
        return releaseService.create(new CreateReleaseRequest(requestId, "tm", "1.0.0", "2.0.0", 100,
                null, null, new TopologyRequest(1, deviceIds, edges))).releaseId();
    }

    @Test
    void 并发领波次_发布单行锁串行_两波次各5台且无重复任务() throws Exception {
        List<String> deviceIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String deviceId = "d0" + i;
            deviceIds.add(deviceId);
            deviceService.register(new RegisterDeviceRequest("req-d" + i, deviceId, "tm", "1.0.0", i));
        }
        long releaseId = createTopologyRelease("req-r", deviceIds, List.of());

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> topologyWaveService.claim(releaseId, new ClaimWaveRequest("req-w1", "op")),
                (Callable<Object>) () -> topologyWaveService.claim(releaseId, new ClaimWaveRequest("req-w2", "op"))));

        List<WaveView> waves = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(WaveView.class);
            waves.add((WaveView) result);
        }
        assertThat(waves).extracting(WaveView::waveSeq).containsExactlyInAnyOrder(1, 2);
        List<String> claimed = new ArrayList<>();
        for (WaveView wave : waves) {
            assertThat(wave.members()).hasSize(5);
            wave.members().forEach(member -> claimed.add(member.deviceId()));
        }
        assertThat(claimed).containsExactlyInAnyOrderElementsOf(deviceIds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE down_flag = TRUE", Long.class))
                .isEqualTo(10);
    }

    @Test
    void 并发同requestId领波次_重放一致且只建一波() throws Exception {
        List<String> deviceIds = List.of("A", "B", "C");
        for (int i = 0; i < deviceIds.size(); i++) {
            deviceService.register(
                    new RegisterDeviceRequest("req-d" + i, deviceIds.get(i), "tm", "1.0.0", i + 1));
        }
        long releaseId = createTopologyRelease("req-r", deviceIds,
                List.of(new TopologyEdgeRequest("A", "B"), new TopologyEdgeRequest("B", "C")));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> topologyWaveService.claim(releaseId, new ClaimWaveRequest("req-same", "op")));
        }
        List<Object> results = runConcurrently(tasks);

        long waveId = -1;
        for (Object result : results) {
            assertThat(result).isInstanceOf(WaveView.class);
            WaveView wave = (WaveView) result;
            assertThat(wave.members()).hasSize(2);
            if (waveId == -1) {
                waveId = wave.waveId();
            }
            assertThat(wave.waveId()).isEqualTo(waveId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_wave", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(2);
    }

    @Test
    void 并发同requestId人工恢复_健康版本只加一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "A", "tm", "1.0.0", 1));
        long releaseId = createTopologyRelease("req-r", List.of("A"), List.of());
        topologyWaveService.claim(releaseId, new ClaimWaveRequest("req-w", "op"));
        assertThat(deviceService.get("A").down()).isTrue();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.recover("A", new RecoverDeviceRequest("req-rec", "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        assertThat(deviceService.get("A").healthVersion()).isEqualTo(2);
        assertThat(deviceService.get("A").down()).isFalse();
    }

    @Test
    void 并发成功回执与取消_停机释放一致且无待执行残留() throws Exception {
        int successWins = 0;
        int cancelWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "tm" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(new CreateReleaseRequest("req-r" + round, model,
                    "1.0.0", "2.0.0", 100, null, null,
                    new TopologyRequest(1, List.of(deviceId), List.of()))).releaseId();
            WaveView wave = topologyWaveService.claim(releaseId,
                    new ClaimWaveRequest("req-w" + round, "op"));
            long taskId = wave.members().get(0).taskId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskId,
                            new ReceiptRequest("req-s" + seq, ReceiptResult.SUCCESS)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "req-x" + seq)));

            Object receiptResult = results.get(0);
            TaskStatus finalStatus = taskRepository.findById(taskId).orElseThrow().status();
            if (finalStatus == TaskStatus.SUCCESS) {
                successWins++;
                assertThat(receiptResult).isNotInstanceOf(Exception.class);
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("2.0.0");
            } else {
                cancelWins++;
                assertThat(finalStatus).isEqualTo(TaskStatus.CANCELLED);
                assertThat(receiptResult).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(deviceService.get(deviceId).currentVersion()).isEqualTo("1.0.0");
            }
            // 两种顺序下停机都必须被释放，且无 PENDING 残留
            assertThat(deviceService.get(deviceId).down()).as("第%d轮", round).isFalse();
            assertThat(finalStatus).isNotEqualTo(TaskStatus.PENDING);
        }
        assertThat(successWins + cancelWins).isEqualTo(10);
    }
}
