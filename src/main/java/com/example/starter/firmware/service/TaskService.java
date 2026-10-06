package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
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
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例、发布单 ACTIVE
     * 且目标安全索引不低于设备当前底线（旧发布未填索引则不适用）时创建，创建时冻结目标索引。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = Fingerprints.of("task.pull", deviceId);
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
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()
                    || belowFloor(device, order.targetSecurityIndex())) {
                return new PullResponse(null);
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId, order.targetSecurityIndex());
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
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     * 安全索引联合裁决：任务冻结目标索引时，SUCCESS 必须携带同一索引；安装提交前重查设备实际底线，
     * 底线已高于冻结索引则 409，不更新版本与监控样本，也不伪造失败；FAILED 不推进底线。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = Fingerprints.of("task.receipt", String.valueOf(taskId), request.result().name(),
                request.securityIndex() == null ? null : String.valueOf(request.securityIndex()));
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    Integer target = task.targetSecurityIndex();
                    Device device = null;
                    if (target != null || request.result() == ReceiptResult.SUCCESS) {
                        device = deviceRepository.findByIdForUpdate(task.deviceId())
                                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND",
                                        "设备不存在: " + task.deviceId()));
                    }
                    if (target != null) {
                        rejectIndexMismatch(task, request);
                        if (device.securityFloor() > target) {
                            throw ApiException.conflict("SECURITY_FLOOR_CONFLICT",
                                    "设备安全底线 " + device.securityFloor() + " 已高于任务目标索引 " + target
                                            + "，回执拒绝");
                        }
                    }
                    taskRepository.complete(taskId, request.result(), request.securityIndex());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        if (target != null) {
                            deviceRepository.completeInstall(task.deviceId(), lockedOrder.toVersion(),
                                    Math.max(device.securityFloor(), target));
                        } else {
                            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                        }
                    }
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    pauseIfThresholdReached(updated, taskId);
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                case SUCCESS, FAILED -> {
                    rejectIndexMismatch(task, request);
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
     * 设备底线高于发布目标索引（旧发布索引为 null 时不适用）。
     */
    private boolean belowFloor(Device device, Integer targetSecurityIndex) {
        return targetSecurityIndex != null && device.securityFloor() > targetSecurityIndex;
    }

    /**
     * 证明索引校验：任务冻结了目标索引时，SUCCESS 必须携带同一索引；
     * 携带了不同索引的回执（含终态任务的新键回执）一律 409。
     */
    private void rejectIndexMismatch(RolloutTask task, ReceiptRequest request) {
        Integer target = task.targetSecurityIndex();
        if (target == null) {
            return;
        }
        if (request.securityIndex() == null) {
            if (task.status() == TaskStatus.PENDING && request.result() == ReceiptResult.SUCCESS) {
                throw ApiException.conflict("SECURITY_INDEX_MISMATCH",
                        "SUCCESS 回执必须携带任务目标索引 " + target);
            }
            return;
        }
        if (!request.securityIndex().equals(target)) {
            throw ApiException.conflict("SECURITY_INDEX_MISMATCH",
                    "证明索引 " + request.securityIndex() + " 与任务目标索引 " + target + " 不一致");
        }
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
