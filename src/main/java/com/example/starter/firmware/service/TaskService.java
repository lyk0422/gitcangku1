package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.QuarantineRound;
import com.example.starter.firmware.domain.QuarantineStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.QuarantineRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁设备行、再锁发布单行，最后操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 设备隔离：同一设备对同一目标固件版本的连续两次 FAILED 在第二次提交时固化两次任务、版本、失败原因，
 * 设备进入 QUARANTINED；隔离期间拉取该版本返回 409。成功回执中断本轮连续失败。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final QuarantineRepository quarantineRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       QuarantineRepository quarantineRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.quarantineRepository = quarantineRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 设备对该发布单目标版本处于 QUARANTINED 时一律返回 409，不返回旧任务也不创建新任务。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            // 设备行锁先行：与回执的隔离计数、解除隔离串行，杜绝隔离后仍领取任务。
            deviceRepository.findByIdForUpdate(device.deviceId());
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return new PullResponse(null);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            quarantineRepository.findActive(device.deviceId(), order.toVersion())
                    .filter(round -> round.status() == QuarantineStatus.QUARANTINED)
                    .ifPresent(round -> {
                        throw ApiException.conflict("DEVICE_QUARANTINED",
                                "设备已被隔离，隔离轮次ID=" + round.id() + "，请先解除隔离");
                    });
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
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 首次 FAILED 按目标版本累计连续失败：第一次固化依据，第二次同版本失败提交时固化两次任务/原因并隔离设备；
     * SUCCESS 中断本轮连续失败。样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name(),
                request.reason() == null ? "" : request.reason(),
                request.operator() == null ? "" : request.operator());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            // 统一加锁顺序：设备行 -> 发布单行 -> 任务行，避免与拉取/解除交叉死锁。
            deviceRepository.findByIdForUpdate(snapshot.deviceId());
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    taskRepository.complete(taskId, request.result(), request.reason());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                        quarantineRepository.findActive(task.deviceId(), lockedOrder.toVersion())
                                .filter(round -> round.status() == QuarantineStatus.TRACKING)
                                .ifPresent(round -> quarantineRepository.resetTracking(round.id()));
                    } else {
                        recordFailureForQuarantine(task, lockedOrder, request);
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
     * 在设备行锁内按目标版本维护连续失败：
     * 无活跃轮次则新建 TRACKING（count=1，固化第一次任务/版本/原因）；
     * TRACKING 且 count=0 时重新固化第一次失败；count=1 时本次为第二次失败，固化两次依据并转 QUARANTINED；
     * 已 QUARANTINED 时已发放任务的迟到 FAILED 只完成任务、不再改动隔离依据。
     */
    private void recordFailureForQuarantine(RolloutTask task, ReleaseOrder order, ReceiptRequest request) {
        String deviceId = task.deviceId();
        String targetVersion = order.toVersion();
        String now = Instant.now(clock).toString();
        QuarantineRound active = quarantineRepository
                .findActiveForUpdate(deviceId, targetVersion).orElse(null);
        if (active == null) {
            int roundNo = quarantineRepository.maxRoundNo(deviceId, targetVersion) + 1;
            quarantineRepository.insertFirstFailure(deviceId, targetVersion, roundNo,
                    task.id(), order.id(), request.reason(), now);
            return;
        }
        if (active.status() == QuarantineStatus.QUARANTINED) {
            return;
        }
        if (active.consecutiveFailures() == 0) {
            quarantineRepository.markFirstFailure(active.id(), task.id(), order.id(), request.reason(), now);
            return;
        }
        if (active.consecutiveFailures() >= 1) {
            if (request.operator() == null || request.operator().isBlank()) {
                throw ApiException.badRequest("OPERATOR_REQUIRED",
                        "连续第二次失败将隔离设备，回执必须提供操作人 operator 作为隔离确认人");
            }
            int updated = quarantineRepository.quarantine(active.id(), task.id(), order.id(),
                    request.reason(), now, request.operator(), now);
            if (updated != 1) {
                throw new IllegalStateException("隔离状态更新未生效，轮次ID=" + active.id());
            }
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
