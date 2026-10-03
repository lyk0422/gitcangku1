package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.domain.ApiException;
import com.example.starter.domain.CalibrationRecord;
import com.example.starter.domain.ReceiveTask;
import com.example.starter.domain.TaskBinding;
import com.example.starter.service.BindingService;
import com.example.starter.service.CalibrationService;
import com.example.starter.web.dto.BindRequest;
import com.example.starter.web.dto.CreateCalibrationRequest;
import com.example.starter.web.dto.CreateTaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * 并发绑定测试：两个重叠任务同时绑定同一天线，最多成功一件；
 * 失败方不占 requestId，可用同一键改绑不重叠任务。
 */
@SpringBootTest
class ConcurrentBindingTest {

    @Autowired
    private CalibrationService calibrationService;

    @Autowired
    private BindingService bindingService;

    @Test
    void concurrentOverlappingBindsAllowOnlyOneSuccess() throws Exception {
        CalibrationRecord calibration = calibrationService.create(new CreateCalibrationRequest(
                "ANT-CONC", Instant.parse("2026-07-01T08:00:00Z"),
                new BigDecimal("0.05"), new BigDecimal("0.05")));
        ReceiveTask taskA = bindingService.createTask(new CreateTaskRequest("CONC-T-A",
                "ANT-CONC", Instant.parse("2026-07-01T10:00:00Z"),
                Instant.parse("2026-07-01T12:00:00Z")));
        ReceiveTask taskB = bindingService.createTask(new CreateTaskRequest("CONC-T-B",
                "ANT-CONC", Instant.parse("2026-07-01T11:00:00Z"),
                Instant.parse("2026-07-01T13:00:00Z")));
        ReceiveTask taskFree = bindingService.createTask(new CreateTaskRequest("CONC-T-FREE",
                "ANT-CONC", Instant.parse("2026-07-01T14:00:00Z"),
                Instant.parse("2026-07-01T15:00:00Z")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            Future<Object> first = pool.submit(() -> bind(gate,
                    new BindRequest("CONC-RQ-A", taskA.id(), calibration.id())));
            Future<Object> second = pool.submit(() -> bind(gate,
                    new BindRequest("CONC-RQ-B", taskB.id(), calibration.id())));
            gate.countDown();
            Object resultA = first.get(30, TimeUnit.SECONDS);
            Object resultB = second.get(30, TimeUnit.SECONDS);

            List<Object> results = List.of(resultA, resultB);
            long successes = results.stream().filter(TaskBinding.class::isInstance).count();
            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast)
                    .filter(e -> e.status() == HttpStatus.CONFLICT
                            && "ANTENNA_OVERLAP".equals(e.reason()))
                    .count();
            assertEquals(1, successes, "并发重叠绑定最多成功一件");
            assertEquals(1, conflicts, "失败方返回 409 天线重叠");

            String loserRequestId = resultA instanceof ApiException ? "CONC-RQ-A" : "CONC-RQ-B";
            assertTrue(bindingService.getByRequestIdOptional(loserRequestId).isEmpty(),
                    "失败不占键");
            TaskBinding rebound = bindingService.bind(
                    new BindRequest(loserRequestId, taskFree.id(), calibration.id()));
            assertEquals(taskFree.id(), rebound.taskId(), "失败方可改绑不重叠任务");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Object bind(CountDownLatch gate, BindRequest request) {
        try {
            gate.await(10, TimeUnit.SECONDS);
            return bindingService.bind(request);
        } catch (ApiException ex) {
            return ex;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}