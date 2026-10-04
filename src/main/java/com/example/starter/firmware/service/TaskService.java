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
import java.util.Locale;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 增量发布：拉取要求设备当前版本与当前摘要均匹配（否则 422 且不生成任务），任务冻结三个摘要
 * 与设备镜像代次；SUCCESS 回执必须携带任务代次、实际基底及实际目标摘要，任一不符 409。
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
     * 增量发布额外要求设备当前摘要等于基底摘要；版本或摘要不匹配返回 422 且不生成任务。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = idempotency.fingerprint("task.pull", deviceId);
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
                if (!device.currentVersion().equals(order.fromVersion())) {
                    throw ApiException.unprocessable("DELTA_VERSION_MISMATCH",
                            "设备当前版本与增量基底版本不一致: " + device.currentVersion());
                }
                if (!device.currentDigest().equals(order.baseDigest())) {
                    throw ApiException.unprocessable("DELTA_BASE_DIGEST_MISMATCH",
                            "设备当前摘要与增量基底摘要不一致");
                }
            } else if (!device.currentVersion().equals(order.fromVersion())) {
                return new PullResponse(null);
            }
            if (device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            boolean delta = order.releaseType() == ReleaseType.DELTA;
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId,
                        delta ? order.baseDigest() : null,
                        delta ? order.patchDigest() : null,
                        delta ? order.targetDigest() : null,
                        delta ? device.imageGeneration() : null);
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
     * 回执：首次回执终结任务并计入完成时所在监控轮次。
     * 增量任务 SUCCESS 必须携带任务代次、实际基底及实际目标摘要，且设备当前代次与摘要仍等于
     * 拉取时冻结值（期间未经维护更新），任一不符 409，不更新设备、不统计成功；
     * 合法 SUCCESS 同事务写入设备当前版本、目标摘要（镜像代次加一）、任务终态与统计。
     * FAILED 只终结任务，不改设备镜像。同结果重复成功（不重复计数），改结果 409；
     * 已取消任务的后到回执 409 且不更新设备版本。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = idempotency.fingerprint("task.receipt", taskId, request.result().name(),
                request.imageGeneration(), lower(request.actualBaseDigest()),
                lower(request.actualTargetDigest()));
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    if (task.baseDigest() != null && request.result() == ReceiptResult.SUCCESS) {
                        validateDeltaReceipt(task, request);
                    }
                    taskRepository.complete(taskId, request.result());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        if (task.baseDigest() != null) {
                            deviceRepository.applyDeltaInstall(task.deviceId(), lockedOrder.toVersion(),
                                    task.targetDigest());
                        } else {
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
     * 增量 SUCCESS 回执门禁：在发布单行锁与任务行锁内再锁设备行，与维护更新串行。
     * 回执携带的代次、基底、目标摘要必须等于任务冻结值，且设备当前代次与摘要未被维护推进。
     */
    private void validateDeltaReceipt(RolloutTask task, ReceiptRequest request) {
        Device device = deviceRepository.findByIdForUpdate(task.deviceId())
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + task.deviceId()));
        if (request.imageGeneration() == null || !request.imageGeneration().equals(task.imageGeneration())) {
            throw ApiException.conflict("RECEIPT_GENERATION_MISMATCH",
                    "回执任务代次与任务冻结代次不一致: " + task.imageGeneration());
        }
        if (device.imageGeneration() != task.imageGeneration()
                || !device.currentDigest().equals(task.baseDigest())) {
            throw ApiException.conflict("DEVICE_IMAGE_ADVANCED",
                    "设备镜像已在拉取后被维护更新，基于旧镜像的补丁不能安装");
        }
        String actualBase = lower(request.actualBaseDigest());
        if (actualBase == null || !actualBase.equals(task.baseDigest())) {
            throw ApiException.conflict("RECEIPT_BASE_DIGEST_MISMATCH", "实际基底摘要与任务冻结基底摘要不一致");
        }
        String actualTarget = lower(request.actualTargetDigest());
        if (actualTarget == null || !actualTarget.equals(task.targetDigest())) {
            throw ApiException.conflict("RECEIPT_TARGET_DIGEST_MISMATCH", "实际目标摘要与任务冻结目标摘要不一致");
        }
    }

    private static String lower(String digest) {
        return digest == null ? null : digest.toLowerCase(Locale.ROOT);
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