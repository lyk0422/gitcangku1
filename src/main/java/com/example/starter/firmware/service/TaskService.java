package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.RejectedReceiptRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取、开始与回执。与隔离、取消、恢复并发时统一先锁设备行、再锁发布单行、
 * 最后锁任务行，形成一致提交顺序。
 * 隔离持续门禁：隔离设备拉取与开始返回 422 DEVICE_QUARANTINED；进行中任务的成功回执返回 422
 * 并保留设备原版本（被拒回执留痕），失败回执终结任务但不计失败率样本。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final RejectedReceiptRepository rejectedReceiptRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       RejectedReceiptRepository rejectedReceiptRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.rejectedReceiptRepository = rejectedReceiptRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：隔离设备 422 DEVICE_QUARANTINED；已存在任务直接返回；否则仅当型号与当前版本匹配、
     * 分桶号小于比例且发布单 ACTIVE 时创建。PAUSED 时不创建新任务，已有任务仍可查看与回执。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.status() == DeviceStatus.QUARANTINED) {
                throw ApiException.unprocessable("DEVICE_QUARANTINED",
                        "设备隔离中，不得拉取新任务: " + deviceId);
            }
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return new PullResponse(null);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return new PullResponse(TaskView.of(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(TaskView.of(task, order));
        }, PullResponse.class);
    }

    /**
     * 设备上报开始刷写：PENDING 转 STARTED；已 STARTED 幂等返回；已终结或已取消 409；
     * 隔离设备 422 DEVICE_QUARANTINED。
     */
    public TaskView start(long taskId, String requestId) {
        String fingerprint = String.join("|", "task.start", String.valueOf(taskId));
        return idempotency.execute(requestId, "task.start", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            Device device = deviceRepository.findByIdForUpdate(snapshot.deviceId())
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在"));
            if (device.status() == DeviceStatus.QUARANTINED) {
                throw ApiException.unprocessable("DEVICE_QUARANTINED",
                        "设备隔离中，不能开始任务: " + snapshot.deviceId());
            }
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    taskRepository.markStarted(taskId);
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), lockedOrder);
                }
                case STARTED -> TaskView.of(task, lockedOrder);
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_FINISHED",
                        "任务已终结为 " + task.status() + "，不能开始");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，不能开始");
            };
        }, TaskView.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 隔离门禁：设备隔离中且任务进行中时，SUCCESS 回执 422（留痕被拒回执、保留原版本、不计样本），
     * FAILED 回执终结任务但不计失败率样本、不触发暂停评估。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        try {
            return idempotency.execute(request.requestId(), "task.receipt", fingerprint,
                    () -> doReceipt(taskId, request), TaskView.class);
        } catch (ApiException e) {
            if ("DEVICE_QUARANTINED".equals(e.code())) {
                recordRejectedReceipt(taskId, request.result());
            }
            throw e;
        }
    }

    private TaskView doReceipt(long taskId, ReceiptRequest request) {
        RolloutTask snapshot = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        Device device = deviceRepository.findByIdForUpdate(snapshot.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在"));
        ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return switch (task.status()) {
            case PENDING, STARTED -> {
                if (device.status() == DeviceStatus.QUARANTINED) {
                    if (request.result() == ReceiptResult.SUCCESS) {
                        throw ApiException.unprocessable("DEVICE_QUARANTINED",
                                "设备隔离中，进行中任务的成功回执不被受理: " + snapshot.deviceId());
                    }
                    // 隔离设备失败回执：终结任务但不计失败率样本、不评估暂停、不改设备版本
                    taskRepository.complete(taskId, request.result());
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                taskRepository.complete(taskId, request.result());
                releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                if (request.result() == ReceiptResult.SUCCESS) {
                    deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                }
                ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                pauseIfThresholdReached(updated, taskId);
                yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
            }
            case SUCCESS, FAILED -> {
                if (task.firstResult() == request.result()) {
                    yield TaskView.of(task, lockedOrder);
                }
                throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                        "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
            }
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
        };
    }

    /**
     * 被拒回执留痕：业务事务已回滚，此处以自动提交单条写入，只增不改。
     */
    private void recordRejectedReceipt(long taskId, ReceiptResult result) {
        taskRepository.findById(taskId).ifPresent(task -> rejectedReceiptRepository.insert(
                task.id(), task.releaseId(), task.deviceId(), result, "DEVICE_QUARANTINED"));
    }

    /**
     * 样本数达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的发布单原子转为 PAUSED。
     * 调用方持有发布单行锁，并发回执串行通过，每轮至多生成一条暂停记录。
     */
    private void pauseIfThresholdReached(ReleaseOrder order, long triggerTaskId) {
        if (order.status() != ReleaseStatus.ACTIVE) {
            return;
        }
        int samples = order.roundSuccess() + order.roundFailed();
        if (samples < order.sampleFloor()) {
            return;
        }
        if ((long) order.roundFailed() * 100 < (long) samples * order.failureThresholdPercent()) {
            return;
        }
        if (releaseRepository.pauseIfActive(order.id()) == 1) {
            pauseRecordRepository.insert(order.id(), order.monitorRound(), triggerTaskId,
                    order.roundSuccess(), order.roundFailed(), Instant.now(clock).toString());
        }
    }

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }
}
