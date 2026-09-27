package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceTagBatchResponse;
import com.example.starter.firmware.api.ReleaseTargetView;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.TagBatchUpdateRequest;
import com.example.starter.firmware.api.TargetPolicyRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TagChangeRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TagService;
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
 * 目标标签策略并发边界测试（真实 H2 事务）：标签批量修改、发布创建按提交顺序裁决；
 * 字典行锁串行化并发变更；同 requestId 并发重放副作用只发生一次。
 */
@SpringBootTest
class FirmwareTargetConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TagService tagService;

    @Autowired
    private TagChangeRepository tagChangeRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_target_snapshot");
        jdbc.update("DELETE FROM release_target_policy");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_tag");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM tag_change_record");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE tag_dictionary SET version = 0 WHERE id = 1");
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

    private TagBatchUpdateRequest addTagRequest(String requestId, String deviceId, String tag) {
        return new TagBatchUpdateRequest(requestId, "op-concurrent",
                List.of(new TagBatchUpdateRequest.TagUpdateItem(deviceId, List.of(tag), null)));
    }

    @Test
    void 并发批量修改与发布创建_按提交顺序裁决且快照与字典版本一致() throws Exception {
        int createWins = 0;
        int updateWins = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model,
                    "1.0.0", 1, List.of("a")));
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> tagService.batchUpdate(
                            addTagRequest("req-b" + seq, deviceId, "b")),
                    (Callable<Object>) () -> releaseService.create(
                            new CreateReleaseRequest("req-r" + seq, model, "1.0.0", "2.0.0", 100,
                                    null, null, new TargetPolicyRequest(List.of("b"), null, null)))));

            Object batchResult = results.get(0);
            Object createResult = results.get(1);
            assertThat(batchResult).as("批量修改始终成功（第%d轮）", round)
                    .isInstanceOf(DeviceTagBatchResponse.class);
            DeviceTagBatchResponse batch = (DeviceTagBatchResponse) batchResult;

            if (createResult instanceof ApiException ae) {
                // 创建先解析快照：设备尚无标签 b，快照为空 422，不残留发布单
                createWins++;
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("TARGET_SNAPSHOT_EMPTY");
                Long releases = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM release_order WHERE model = ?", Long.class, model);
                assertThat(releases).isZero();
            } else {
                // 批量修改先提交：快照包含该设备，且固化的字典版本不早于批量修改后的版本
                updateWins++;
                ReleaseView created = (ReleaseView) createResult;
                ReleaseTargetView target = releaseService.target(created.releaseId());
                assertThat(target.devices()).containsExactly(deviceId);
                assertThat(target.tagDictVersion()).isGreaterThanOrEqualTo(batch.dictVersion());
            }
        }
        assertThat(createWins + updateWins).isEqualTo(10);
    }

    @Test
    void 并发批量修改_字典版本连续递增且历史完整() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1,
                List.of("a")));
        deviceService.register(new RegisterDeviceRequest("req-d2", "d2", "m1", "1.0.0", 2,
                List.of("b")));

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> tagService.batchUpdate(addTagRequest("req-b1", "d1", "x")),
                (Callable<Object>) () -> tagService.batchUpdate(addTagRequest("req-b2", "d2", "y"))));
        assertThat(results).allMatch(r -> r instanceof DeviceTagBatchResponse);

        long v1 = ((DeviceTagBatchResponse) results.get(0)).dictVersion();
        long v2 = ((DeviceTagBatchResponse) results.get(1)).dictVersion();
        // 两次登记后为版本2，两次批量修改串行推进到3与4
        assertThat(List.of(v1, v2)).containsExactlyInAnyOrder(3L, 4L);

        var history = tagChangeRepository.findAll();
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(h -> h.dictVersion())).containsExactlyInAnyOrder(3L, 4L);

        assertThat(deviceService.get("d1").tags()).containsExactly("a", "x");
        assertThat(deviceService.get("d2").tags()).containsExactly("b", "y");
    }

    @Test
    void 并发同requestId批量修改_重放一致且副作用只发生一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1,
                List.of("a")));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> tagService.batchUpdate(addTagRequest("req-same", "d1", "x")));
        }
        List<Object> results = runConcurrently(tasks);

        DeviceTagBatchResponse first = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(DeviceTagBatchResponse.class);
            DeviceTagBatchResponse response = (DeviceTagBatchResponse) result;
            if (first == null) {
                first = response;
            } else {
                assertThat(response.dictVersion()).isEqualTo(first.dictVersion());
                assertThat(response.changedAtUtc()).isEqualTo(first.changedAtUtc());
            }
        }
        // 字典版本只推进一次（登记后为1，批量修改后为2），历史仅一条
        assertThat(first.dictVersion()).isEqualTo(2);
        assertThat(tagChangeRepository.findAll()).hasSize(1);
        assertThat(deviceService.get("d1").tags()).containsExactly("a", "x");
    }

    @Test
    void 并发创建带策略发布单_同型号至多一张且快照只生成一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1,
                List.of("edge")));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int seq = i;
            tasks.add(() -> releaseService.create(new CreateReleaseRequest("req-c" + seq, "m1",
                    "1.0.0", "2.0.0", 100, null, null,
                    new TargetPolicyRequest(List.of("edge"), null, null))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof ReleaseView).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        // 快照只为成功创建生成一次
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_policy", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_target_snapshot", Long.class))
                .isEqualTo(1);
    }
}
