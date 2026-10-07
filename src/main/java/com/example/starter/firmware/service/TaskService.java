package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullRequest;
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
import com.example.starter.firmware.domain.TaskType;
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
import java.util.UUID;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * BOOT_CHALLENGE 任务：领取时持设备行锁冻结启动计数基线并生成唯一挑战串；
 * 回执须携带匹配挑战与非空操作者，SUCCESS 还要求计数严格大于基线与提交时设备实际计数，
 * 证明校验在发布单行锁与设备行锁内完成，与计数维护、取消串行裁决。
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
     * 兼容旧调用方：仅携带 requestId 的普通任务拉取。
     */
    public PullResponse pull(String deviceId, String requestId) {
        return pull(deviceId, new PullRequest(requestId, null, null));
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * BOOT_CHALLENGE 类型领取时在设备行锁内冻结当前启动计数基线并生成唯一挑战串。
     */
    public PullResponse pull(String deviceId, PullRequest request) {
        TaskType taskType = parseTaskType(request.taskType());
        if (taskType == TaskType.BOOT_CHALLENGE) {
            requireOperator(request.operator());
        }
        String fingerprint = String.join("|", "task.pull", deviceId, taskType.name(),
                request.operator() == null ? "" : request.operator());
        return idempotency.execute(request.requestId(), "task.pull", fingerprint, () -> {
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
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            long taskId;
            try {
                if (taskType == TaskType.BOOT_CHALLENGE) {
                    Device locked = deviceRepository.findByIdForUpdate(deviceId).orElseThrow();
                    taskId = taskRepository.insert(order.id(), deviceId, TaskType.BOOT_CHALLENGE,
                            UUID.randomUUID().toString(), locked.bootCount());
                } else {
                    taskId = taskRepository.insert(order.id(), deviceId, TaskType.NORMAL, null, null);
                }
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
     * BOOT_CHALLENGE 任务任何状态都先校验挑战串与首次证明一致（错误 409 且不落任何变更）；
     * PENDING 的 SUCCESS 还要求计数严格大于冻结基线与提交时设备实际计数，
     * 成功时原子更新设备计数、当前版本、任务与本轮监控样本；FAILED 只校验挑战、不提升计数。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        Long bootCount = BootCountParser.parseOptional(request.bootCount());
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId),
                request.result().name(),
                request.challenge() == null ? "" : request.challenge(),
                bootCount == null ? "" : String.valueOf(bootCount),
                request.operator() == null ? "" : request.operator());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (task.taskType() == TaskType.BOOT_CHALLENGE) {
                requireOperator(request.operator());
                requireMatchingChallenge(task, request.challenge());
            }
            return switch (task.status()) {
                case PENDING -> {
                    if (task.taskType() == TaskType.BOOT_CHALLENGE
                            && request.result() == ReceiptResult.SUCCESS) {
                        installWithProof(task, bootCount);
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
        }, TaskView.class);
    }

    /**
     * 挑战任务安装证明：计数须严格大于冻结基线与设备实际计数（持设备行锁与维护串行），
     * 通过后在同一事务推进设备启动计数；维护到更高计数后的旧回执在此被拒绝。
     */
    private void installWithProof(RolloutTask task, Long bootCount) {
        if (bootCount == null) {
            throw ApiException.badRequest("BOOT_COUNT_REQUIRED", "启动挑战任务 SUCCESS 回执须携带启动计数");
        }
        Device device = deviceRepository.findByIdForUpdate(task.deviceId()).orElseThrow();
        if (bootCount <= task.frozenBootCount() || bootCount <= device.bootCount()) {
            throw ApiException.conflict("STALE_BOOT_COUNT",
                    "启动计数须严格大于冻结基线 " + task.frozenBootCount()
                            + " 与设备当前计数 " + device.bootCount());
        }
        deviceRepository.updateBootCount(task.deviceId(), bootCount);
    }

    private static TaskType parseTaskType(String raw) {
        if (raw == null || raw.isBlank()) {
            return TaskType.NORMAL;
        }
        try {
            return TaskType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_TASK_TYPE", "未知任务类型: " + raw);
        }
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw ApiException.badRequest("OPERATOR_REQUIRED", "启动挑战写入须携带非空操作者");
        }
    }

    private static void requireMatchingChallenge(RolloutTask task, String challenge) {
        if (challenge == null || !challenge.equals(task.challenge())) {
            throw ApiException.conflict("CHALLENGE_MISMATCH", "挑战串与任务首次证明不一致");
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
