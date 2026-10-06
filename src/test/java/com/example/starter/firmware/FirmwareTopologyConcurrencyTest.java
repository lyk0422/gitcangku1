package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceEdge;
import com.example.starter.firmware.api.RecoverDeviceRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.WaveClaimRequest;
import com.example.starter.firmware.api.WaveView;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseType;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.WaveRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import com.example.starter.firmware.service.WaveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拓扑波次并发测试：真实并发打到 H2 事务与唯一约束上，验证波次领取、人工恢复与取消的一致性。
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
    private WaveService waveService;

    @Autowired
    private WaveRepository waveRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM release_wave_member");
        jdbc.update("DELETE FROM release_wave");
        jdbc.update("DELETE FROM release_topology_edge");
        jdbc.update("DELETE FROM release_topology_device");
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

    private void register(String deviceId) {
        deviceService.register(new RegisterDeviceRequest("reg-" + deviceId, deviceId, "m1", "1.0.0", 1));
    }

    private long createTopology(String requestId, List<String> devices, List<DeviceEdge> edges) {
        return releaseService.create(new CreateReleaseRequest(requestId, "m1", "1.0.0", "2.0.0", 100,
                null, null, ReleaseType.TOPOLOGY, 1, devices, edges)).releaseId();
    }

    @Test
    void 并发领取_成员互斥且每台设备至多一个任务() throws Exception {
        for (int i = 1; i <= 6; i++) {
            register("d" + i);
        }
        long releaseId = createTopology("r1",
                List.of("d1", "d2", "d3", "d4", "d5", "d6"), List.of());

        int threads = 8;
        List<Callable<WaveView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> waveService.claim(releaseId, new WaveClaimRequest("w" + seq, "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);

        Set<String> members = new HashSet<>();
        int successes = 0;
        int unprocessable = 0;
        for (Object result : results) {
            if (result instanceof WaveView view) {
                successes++;
                for (String member : view.members()) {
                    assertThat(members.add(member)).as("设备 %s 被重复选入波次", member).isTrue();
                }
            } else {
                assertThat(result).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
                unprocessable++;
            }
        }
        assertThat(successes).isBetween(1, 2);
        assertThat(successes + unprocessable).isEqualTo(threads);
        // 6 台设备全部被某一波次整体领取，每台至多一个任务
        assertThat(members).containsExactlyInAnyOrder("d1", "d2", "d3", "d4", "d5", "d6");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(6);
        Long down = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device WHERE health_status = 'DOWN'", Long.class);
        assertThat(down).isEqualTo(6);
    }

    @Test
    void 并发同requestId领取_重放一致且只创建一个波次() throws Exception {
        for (int i = 1; i <= 4; i++) {
            register("d" + i);
        }
        long releaseId = createTopology("r1", List.of("d1", "d2", "d3", "d4"), List.of());

        int threads = 6;
        List<Callable<WaveView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> waveService.claim(releaseId, new WaveClaimRequest("w-same", "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);

        Long waveId = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(WaveView.class);
            WaveView view = (WaveView) result;
            if (waveId == null) {
                waveId = view.waveId();
            }
            assertThat(view.waveId()).isEqualTo(waveId);
        }
        assertThat(waveRepository.countByRelease(releaseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(4);
    }

    @Test
    void 并发人工恢复同键_健康版本只加一次() throws Exception {
        register("A");
        long releaseId = createTopology("r1", List.of("A"), List.of());
        WaveView wave = waveService.claim(releaseId, new WaveClaimRequest("w1", "op", 1));
        long taskId = wave.tasks().get(0).taskId();
        taskService.receipt(taskId, new ReceiptRequest("rc-1", ReceiptResult.FAILED));
        assertThat(deviceService.get("A").healthStatus()).isEqualTo("UNHEALTHY");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceService.recover("A", new RecoverDeviceRequest("rec-same", "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(deviceService.get("A").healthStatus()).isEqualTo("HEALTHY");
        assertThat(deviceService.get("A").healthVersion()).isEqualTo(2);
    }

    @Test
    void 并发领取与取消_最终状态一致且停机被释放() throws Exception {
        for (int round = 0; round < 6; round++) {
            String a = "A" + round;
            String b = "B" + round;
            String c = "C" + round;
            register(a);
            register(b);
            register(c);
            long releaseId = createTopology("r" + round, List.of(a, b, c),
                    List.of(new DeviceEdge(a, b), new DeviceEdge(b, c)));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> waveService.claim(releaseId,
                            new WaveClaimRequest("w" + seq, "op", 1)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "x" + seq)));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                    Long.class, releaseId);
            assertThat(pending).as("取消后不得残留 PENDING 任务（第%d轮）", round).isZero();
            Long down = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM device WHERE device_id IN (?, ?, ?) AND health_status = 'DOWN'",
                    Long.class, a, b, c);
            assertThat(down).as("取消后不得残留停机设备（第%d轮）", round).isZero();
        }
    }
}
