package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.RoundStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceFailureRoundRepository;
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
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁设备行、再锁发布单行，最后锁任务，
 * 形成一致提交顺序。回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * FAILED 回执同时在设备行锁内维护"设备+目标版本"连续失败轮次：第一次失败建 ACTIVE 轮次，
 * 第二次失败同事务固化两次任务、版本与失败原因并转 QUARANTINED；隔离期间拉取直接 409。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceFailureRoundRepository roundRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, DeviceFailureRoundRepository roundRepository,
                       PauseRecordRepository pauseRecordRepository,
                       ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.roundRepository = roundRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 设备对该发布单目标版本处于 QUARANTINED 时一律 409，隔离期间不返回任何（含历史失败）任务。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device lockedDevice = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            var activeOrder = releaseRepository.findActiveByModel(lockedDevice.model());
            if (activeOrder.isEmpty()) {
                return new PullResponse(null);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            DeviceFailureRound activeRound = roundRepository
                    .findActive(lockedDevice.deviceId(), order.toVersion()).orElse(null);
            if (activeRound != null && activeRound.status() == RoundStatus.QUARANTINED) {
                throw ApiException.conflict("DEVICE_QUARANTINED",
                        "设备已对版本 " + order.toVersion() + " 隔离，解除审核前不能领取新任务");
            }
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return new PullResponse(TaskView.of(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !lockedDevice.currentVersion().equals(order.fromVersion())
                    || lockedDevice.bucketNo() >= order.ratio()) {
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
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     * FAILED 首次回执另在设备行锁内累计"设备+目标版本"连续失败：第一次记录 ACTIVE 轮次，
     * 第二次固化两次任务与失败原因并转 QUARANTINED；同 requestId 重放不重复计数。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name(),
                request.failureReason() == null ? "" : request.failureReason(),
                request.operatorId() == null ? "" : request.operatorId());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            Device lockedDevice = deviceRepository.findByIdForUpdate(snapshot.deviceId())
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + snapshot.deviceId()));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    taskRepository.complete(taskId, request.result());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(lockedDevice.deviceId(), lockedOrder.toVersion());
                    } else {
                        recordConsecutiveFailure(lockedDevice.deviceId(), lockedOrder.toVersion(), taskId, request);
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
     * 在设备行锁内维护连续失败轮次（调用方已锁设备行与发布单行）：
     * 无活动轮次：以第一次失败开启 ACTIVE 轮次；
     * ACTIVE 轮次：第二次失败提交，固化两次任务、版本与失败原因并原子转 QUARANTINED；
     * QUARANTINED 轮次：隔离后不应再产生同版本 PENDING 任务，防御性跳过，不重复隔离。
     */
    private void recordConsecutiveFailure(String deviceId, String toVersion, long taskId, ReceiptRequest request) {
        var active = roundRepository.findActive(deviceId, toVersion);
        if (active.isEmpty()) {
            int roundNo = roundRepository.nextRoundNo(deviceId, toVersion);
            roundRepository.insert(deviceId, toVersion, roundNo, taskId, request.failureReason(),
                    DeviceFailureRoundRepository.activeKey(deviceId, toVersion));
            return;
        }
        DeviceFailureRound round = active.get();
        if (round.status() == RoundStatus.ACTIVE) {
            int affected = roundRepository.quarantine(round.id(), taskId, request.failureReason(),
                    request.operatorId(), Instant.now(clock).toString());
            if (affected != 1) {
                throw new IllegalStateException("隔离状态流转失败: roundId=" + round.id());
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
