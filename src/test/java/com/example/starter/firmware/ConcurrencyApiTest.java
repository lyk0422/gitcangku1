package com.example.starter.firmware;

import com.example.starter.firmware.dto.CancelRolloutRequest;
import com.example.starter.firmware.dto.CreateRolloutRequest;
import com.example.starter.firmware.dto.PullTaskRequest;
import com.example.starter.firmware.dto.PullTaskResponse;
import com.example.starter.firmware.dto.ReceiptRequest;
import com.example.starter.firmware.dto.RegisterDeviceRequest;
import com.example.starter.firmware.dto.RolloutResponse;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.ApiResult;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.RolloutService;
import com.example.starter.firmware.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发与幂等边界：拉取/回执与取消的提交顺序一致性、同 requestId 并发去重、
 * 同设备同发布单并发拉取只建一条任务。
 */
class ConcurrencyApiTest extends BaseIntegrationTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private RolloutService rolloutService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private ObjectMapper objectMapper;

    private void registerDevice(String deviceId, int bucket) {
        deviceService.register(new RegisterDeviceRequest("setup-" + deviceId, deviceId,
                "model-a", "1.0.0", bucket));
    }

    private long createRollout(int ratio) {
        ApiResult result = rolloutService.create(
                new CreateRolloutRequest("setup-rollout-" + ratio + "-" + System.nanoTime(),
                        "model-a", "1.0.0", "2.0.0", ratio));
        return ((RolloutResponse) result.body()).id();
    }

    private long pullTask(long rolloutId, String deviceId) {
        ApiResult result = taskService.pull(rolloutId,
                new PullTaskRequest("setup-pull-" + deviceId, deviceId));
        return ((PullTaskResponse) result.body()).task().id();
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM rollout_task WHERE id = ?",
                String.class, taskId);
    }

    private String deviceVersion(String deviceId) {
        return jdbc.queryForObject("SELECT firmware_version FROM device WHERE device_id = ?",
                String.class, deviceId);
    }

    @Test
    void pullAndCancelFormConsistentCommitOrder() throws Exception {
        registerDevice("dev-1", 1);
        long rolloutId = createRollout(100);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ApiResult> pull = pool.submit(() -> {
                ready.countDown();
                start.await();
                return taskService.pull(rolloutId, new PullTaskRequest("p-1", "dev-1"));
            });
            Future<ApiResult> cancel = pool.submit(() -> {
                ready.countDown();
                start.await();
                return rolloutService.cancel(rolloutId, new CancelRolloutRequest("x-1"));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ApiResult pullResult = pull.get(15, TimeUnit.SECONDS);
            ApiResult cancelResult = cancel.get(15, TimeUnit.SECONDS);

            assertThat(cancelResult.status()).isEqualTo(200);
            PullTaskResponse pullBody = objectMapper.convertValue(pullResult.body(), PullTaskResponse.class);
            Integer taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Integer.class);
            if (pullBody.task() != null) {
                // 拉取先提交：任务已创建，随后被取消置为 CANCELLED
                assertThat(taskCount).isEqualTo(1);
                assertThat(taskStatus(pullBody.task().id())).isEqualTo("CANCELLED");
            } else {
                // 取消先提交：拉取只能看到 CANCELLED，不创建任务
                assertThat(pullBody.reason()).isEqualTo("ROLLOUT_NOT_ACTIVE");
                assertThat(taskCount).isZero();
            }
            assertThat(jdbc.queryForObject("SELECT status FROM rollout WHERE id = ?",
                    String.class, rolloutId)).isEqualTo("CANCELLED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void successReceiptAndCancelFormConsistentCommitOrder() throws Exception {
        registerDevice("dev-1", 1);
        long rolloutId = createRollout(100);
        long taskId = pullTask(rolloutId, "dev-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> receipt = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return taskService.receipt(taskId, new ReceiptRequest("r-1", "SUCCESS"));
                } catch (ApiException e) {
                    return e;
                }
            });
            Future<ApiResult> cancel = pool.submit(() -> {
                ready.countDown();
                start.await();
                return rolloutService.cancel(rolloutId, new CancelRolloutRequest("x-1"));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object receiptOutcome = receipt.get(15, TimeUnit.SECONDS);
            ApiResult cancelResult = cancel.get(15, TimeUnit.SECONDS);

            assertThat(cancelResult.status()).isEqualTo(200);
            if (receiptOutcome instanceof ApiResult ok) {
                // 回执先提交：任务 SUCCESS 且设备版本已升级，取消不回滚
                assertThat(ok.status()).isEqualTo(200);
                assertThat(taskStatus(taskId)).isEqualTo("SUCCESS");
                assertThat(deviceVersion("dev-1")).isEqualTo("2.0.0");
            } else {
                // 取消先提交：后到回执 409，任务 CANCELLED，设备版本不变
                ApiException error = (ApiException) receiptOutcome;
                assertThat(error.getStatus().value()).isEqualTo(409);
                assertThat(error.getCode()).isEqualTo("TASK_CANCELLED");
                assertThat(taskStatus(taskId)).isEqualTo("CANCELLED");
                assertThat(deviceVersion("dev-1")).isEqualTo("1.0.0");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestIdPullExecutesOnceAndReplays() throws Exception {
        registerDevice("dev-1", 1);
        long rolloutId = createRollout(100);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return taskService.pull(rolloutId, new PullTaskRequest("p-same", "dev-1"));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<Long> taskIds = new HashSet<>();
            for (Future<ApiResult> future : futures) {
                ApiResult result = future.get(20, TimeUnit.SECONDS);
                PullTaskResponse body = objectMapper.convertValue(result.body(), PullTaskResponse.class);
                assertThat(body.task()).isNotNull();
                taskIds.add(body.task().id());
            }
            // 全部请求拿到同一个任务，业务只执行一次
            assertThat(taskIds).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = 'p-same'", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPullsWithDifferentRequestIdsCreateSingleTask() throws Exception {
        registerDevice("dev-1", 1);
        long rolloutId = createRollout(100);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "p-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return taskService.pull(rolloutId, new PullTaskRequest(requestId, "dev-1"));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Set<Long> taskIds = new HashSet<>();
            for (Future<ApiResult> future : futures) {
                ApiResult result = future.get(20, TimeUnit.SECONDS);
                PullTaskResponse body = objectMapper.convertValue(result.body(), PullTaskResponse.class);
                assertThat(body.task()).isNotNull();
                taskIds.add(body.task().id());
            }
            // 唯一约束 + 已有任务优先：同设备同发布单最多一条任务
            assertThat(taskIds).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
