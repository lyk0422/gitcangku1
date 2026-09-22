package com.example.starter.firmware.service;

import com.example.starter.firmware.dto.DeviceResponse;
import com.example.starter.firmware.dto.PullTaskRequest;
import com.example.starter.firmware.dto.PullTaskResponse;
import com.example.starter.firmware.dto.ReceiptRequest;
import com.example.starter.firmware.dto.RolloutResponse;
import com.example.starter.firmware.dto.TaskResponse;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repository.DeviceRepository;
import com.example.starter.firmware.repository.RolloutRepository;
import com.example.starter.firmware.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 投放任务：设备拉取、回执终结、明细查询。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final RolloutRepository rolloutRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotencyService;

    public TaskService(TaskRepository taskRepository,
                       RolloutRepository rolloutRepository,
                       DeviceRepository deviceRepository,
                       IdempotencyService idempotencyService) {
        this.taskRepository = taskRepository;
        this.rolloutRepository = rolloutRepository;
        this.deviceRepository = deviceRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 设备拉取任务：已有任务直接返回；否则仅型号与当前版本匹配、分桶号小于比例
     * 且发布单 ACTIVE 时创建任务，同设备同发布单最多一条。
     */
    public ApiResult pull(long rolloutId, PullTaskRequest request) {
        String fingerprint = rolloutId + "|" + request.deviceId();
        return idempotencyService.execute(request.requestId(), "TASK_PULL", fingerprint,
                () -> doPull(rolloutId, request.deviceId()));
    }

    private ApiResult doPull(long rolloutId, String deviceId) {
        DeviceResponse device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备未登记: " + deviceId));
        // 行锁串行化拉取与取消：取消提交后拉取只能看到 CANCELLED，反之任务先创建再被取消
        RolloutResponse rollout = rolloutRepository.findByIdForUpdate(rolloutId)
                .orElseThrow(() -> ApiException.notFound("ROLLOUT_NOT_FOUND", "发布单不存在: " + rolloutId));

        var existing = taskRepository.findByRolloutAndDevice(rolloutId, deviceId);
        if (existing.isPresent()) {
            return ApiResult.ok(new PullTaskResponse(existing.get(), null));
        }
        if (!"ACTIVE".equals(rollout.status())) {
            return ApiResult.ok(new PullTaskResponse(null, "ROLLOUT_NOT_ACTIVE"));
        }
        if (!device.model().equals(rollout.model())) {
            return ApiResult.ok(new PullTaskResponse(null, "MODEL_MISMATCH"));
        }
        if (!device.firmwareVersion().equals(rollout.fromVersion())) {
            return ApiResult.ok(new PullTaskResponse(null, "VERSION_MISMATCH"));
        }
        if (device.bucketNo() >= rollout.ratio()) {
            return ApiResult.ok(new PullTaskResponse(null, "OUT_OF_BUCKET"));
        }
        long taskId = taskRepository.insert(rolloutId, deviceId,
                rollout.fromVersion(), rollout.toVersion());
        TaskResponse task = new TaskResponse(taskId, rolloutId, deviceId,
                rollout.fromVersion(), rollout.toVersion(), "PENDING");
        return ApiResult.created(new PullTaskResponse(task, null));
    }

    /**
     * 任务回执：首次回执终结任务，仅 SUCCESS 更新设备当前版本；同结果重复成功，
     * 改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     */
    public ApiResult receipt(long taskId, ReceiptRequest request) {
        String fingerprint = taskId + "|" + request.result();
        return idempotencyService.execute(request.requestId(), "TASK_RECEIPT", fingerprint,
                () -> doReceipt(taskId, request.result()));
    }

    private ApiResult doReceipt(long taskId, String result) {
        // 行锁串行化回执与取消：取消先提交则此处看到 CANCELLED，回执先提交则取消跳过该任务
        TaskResponse task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        switch (task.status()) {
            case "PENDING" -> {
                taskRepository.finish(taskId, result);
                if ("SUCCESS".equals(result)) {
                    deviceRepository.updateFirmwareVersion(task.deviceId(), task.toVersion());
                }
                return ApiResult.ok(new TaskResponse(task.id(), task.rolloutId(), task.deviceId(),
                        task.fromVersion(), task.toVersion(), result));
            }
            case "CANCELLED" -> throw ApiException.conflict("TASK_CANCELLED",
                    "任务已取消，回执拒绝: " + taskId);
            default -> {
                // SUCCESS / FAILED 已终结
                if (task.status().equals(result)) {
                    return ApiResult.ok(task);
                }
                throw ApiException.conflict("RECEIPT_CONFLICT",
                        "任务已以 " + task.status() + " 终结，不能改为 " + result);
            }
        }
    }

    /**
     * 查询发布单下全部任务明细。
     */
    public List<TaskResponse> listByRollout(long rolloutId) {
        rolloutRepository.findById(rolloutId)
                .orElseThrow(() -> ApiException.notFound("ROLLOUT_NOT_FOUND", "发布单不存在: " + rolloutId));
        return taskRepository.findByRolloutId(rolloutId);
    }

    /**
     * 查询单个任务明细。
     */
    public TaskResponse getTask(long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
    }
}
