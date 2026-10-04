package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.ReleaseType;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
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
            if (order.status() != ReleaseStatus.ACTIVE) {
                return new PullResponse(null);
            }
            if (order.releaseType() == ReleaseType.DELTA) {
                return pullDelta(order, deviceId);
            }
            if (!device.currentVersion().equals(order.fromVersion())
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
     * 增量拉取：设备行锁内重新读取设备，当前版本与摘要均须匹配基底，否则 422 且不生成任务；
     * 命中后冻结三个摘要与设备镜像代次。与本地维护更新串行，不会基于旧镜像生成任务。
     */
    private PullResponse pullDelta(ReleaseOrder order, String deviceId) {
        Device device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
        if (!device.currentVersion().equals(order.fromVersion())) {
            throw ApiException.unprocessable("VERSION_MISMATCH",
                    "设备当前版本与增量基底版本不一致: " + device.currentVersion());
        }
        if (!device.currentDigest().equals(order.baseDigest())) {
            throw ApiException.unprocessable("DIGEST_MISMATCH", "设备当前镜像摘要与增量基底摘要不一致");
        }
        if (device.bucketNo() >= order.ratio()) {
            return new PullResponse(null);
        }
        long taskId;
        try {
            taskId = taskRepository.insertDelta(order.id(), deviceId, order.baseDigest(),
                    order.patchDigest(), order.targetDigest(), device.imageGeneration());
        } catch (DuplicateKeyException e) {
            RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                    .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
            return new PullResponse(TaskView.of(task, order));
        }
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
        return new PullResponse(TaskView.of(task, order));
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = idempotency.structuredFingerprint("task.receipt", taskId,
                request.result().name(), request.taskGeneration(), request.actualBaseDigest(),
                request.actualTargetDigest());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    if (request.result() == ReceiptResult.SUCCESS && task.targetDigest() != null) {
                        applyDeltaReceipt(task, request, lockedOrder);
                    } else {
                        taskRepository.complete(taskId, request.result());
                        releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                        if (request.result() == ReceiptResult.SUCCESS) {
                            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                        }
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
        }, TaskView.class);
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

    /**
     * 增量任务 SUCCESS：回执必须携带任务代次、实际基底与实际目标摘要且与任务冻结值一致，
     * 任一不符 409，不更新设备、不统计成功；设备当前镜像代次须仍等于任务冻结代次，
     * 防止本地维护后的新镜像装上基于旧镜像的补丁。合法时同一事务写任务终态、统计与设备镜像。
     */
    private void applyDeltaReceipt(RolloutTask task, ReceiptRequest request, ReleaseOrder lockedOrder) {
        if (request.taskGeneration() == null || !request.taskGeneration().equals(task.imageGeneration())) {
            throw ApiException.conflict("RECEIPT_GENERATION_MISMATCH",
                    "回执任务代次与任务冻结代次不一致: " + task.imageGeneration());
        }
        if (!task.baseDigest().equals(request.actualBaseDigest())) {
            throw ApiException.conflict("RECEIPT_BASE_MISMATCH", "回执实际基底摘要与任务冻结基底摘要不一致");
        }
        if (!task.targetDigest().equals(request.actualTargetDigest())) {
            throw ApiException.conflict("RECEIPT_TARGET_MISMATCH", "回执实际目标摘要与任务冻结目标摘要不一致");
        }
        Device device = deviceRepository.findByIdForUpdate(task.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + task.deviceId()));
        if (device.imageGeneration() != task.imageGeneration()) {
            throw ApiException.conflict("DEVICE_IMAGE_CHANGED",
                    "设备镜像代次已变更为 " + device.imageGeneration() + "，基于旧镜像的回执不再受理");
        }
        taskRepository.complete(task.id(), request.result());
        releaseRepository.incrementRoundStats(task.releaseId(), request.result());
        deviceRepository.applyDelta(task.deviceId(), lockedOrder.toVersion(), task.targetDigest());
    }

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }
}
