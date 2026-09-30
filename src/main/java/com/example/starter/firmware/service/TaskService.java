package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.DeviceFailureRoundStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
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
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁设备行、再锁发布单行，最后操作任务，
 * 形成 device -> release -> task 的一致锁序。回执首次终结任务时在发布单行锁内累计当前监控轮次统计，
 * 达到阈值即在同一事务原子暂停。
 *
 * 设备隔离：同一设备同一目标版本连续两次失败（可跨发布单）后，第二次失败同事务将失败轮次置为
 * QUARANTINED 并固化两次任务、版本、失败原因与确认人；隔离期间该设备拉取一律 409，不创建新任务。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceFailureRoundRepository failureRoundRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository,
                       DeviceFailureRoundRepository failureRoundRepository,
                       PauseRecordRepository pauseRecordRepository,
                       ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.failureRoundRepository = failureRoundRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。设备对该目标版本处于 QUARANTINED 时返回 409，
     * 设备行锁保证与第二次失败回执的竞争串行，不会产生隔离后仍可领取的任务。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return new PullResponse(null);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            DeviceFailureRound latestRound = failureRoundRepository
                    .findLatestForUpdate(deviceId, order.toVersion()).orElse(null);
            if (latestRound != null && latestRound.status() == DeviceFailureRoundStatus.QUARANTINED) {
                throw ApiException.conflict("DEVICE_QUARANTINED",
                        "设备已被隔离，禁止领取版本 " + order.toVersion() + " 的投放任务");
            }
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
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     *
     * 失败回执同时维护设备-版本连续失败轮次：COUNTING 轮次上再失败即第二次失败，同事务隔离并固化依据；
     * 无轮次或上一轮已解除/已恢复时另开新一轮，历史行只增不删。SUCCESS 将 COUNTING 轮次自然终结。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            deviceRepository.findByIdForUpdate(snapshot.deviceId())
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
                        deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                        recoverCountingRound(task.deviceId(), lockedOrder.toVersion());
                    } else {
                        recordFailureRound(task.deviceId(), lockedOrder.toVersion(), taskId, request);
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
     * 记录一次失败到设备-版本轮次：已有 COUNTING 轮次时为连续第二次失败，原子转 QUARANTINED 并固化
     * 两次任务、版本、失败原因、确认人与 UTC 时刻；否则按 MAX(round_no)+1 另开新一轮（计数独立，历史不重算）。
     * 调用方持有设备行锁，并发回执串行通过，唯一索引 uk_failure_round_counting 兜底。
     */
    private void recordFailureRound(String deviceId, String toVersion, long taskId, ReceiptRequest request) {
        var latest = failureRoundRepository.findLatestForUpdate(deviceId, toVersion);
        if (latest.isPresent() && latest.get().status() == DeviceFailureRoundStatus.COUNTING) {
            int updated = failureRoundRepository.markQuarantined(latest.get().id(), taskId,
                    request.effectiveFailureReason(), request.effectiveOperator(), Instant.now(clock).toString());
            if (updated != 1) {
                throw new IllegalStateException("第二次失败固化隔离影响行数异常: " + updated);
            }
            return;
        }
        if (latest.isPresent() && latest.get().status() == DeviceFailureRoundStatus.QUARANTINED) {
            // 防御性分支：锁序保证隔离后不可能再领取到 PENDING 任务，出现即说明数据不变量被破坏。
            throw ApiException.conflict("DEVICE_QUARANTINED",
                    "设备已被隔离，禁止再提交版本 " + toVersion + " 的失败回执");
        }
        int nextRoundNo = failureRoundRepository.maxRoundNo(deviceId, toVersion) + 1;
        try {
            failureRoundRepository.insertFirst(deviceId, toVersion, nextRoundNo, taskId,
                    request.effectiveFailureReason());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("失败轮次唯一约束冲突", e);
        }
    }

    /**
     * 设备成功升级到目标版本：同版本 COUNTING 轮次自然终结为 RECOVERED；
     * QUARANTINED/RELEASED/RECOVERED 轮次不受影响，历史保留。
     */
    private void recoverCountingRound(String deviceId, String toVersion) {
        failureRoundRepository.findLatestForUpdate(deviceId, toVersion).ifPresent(round -> {
            if (round.status() == DeviceFailureRoundStatus.COUNTING) {
                failureRoundRepository.markRecoveredIfCounting(round.id());
            }
        });
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
