package com.example.starter.firmware.service;

import com.example.starter.firmware.api.LeaseAcquireRequest;
import com.example.starter.firmware.api.LeaseRenewRequest;
import com.example.starter.firmware.api.LeaseReceiptRequest;
import com.example.starter.firmware.api.LeaseView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskLease;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.LeaseRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    /** 租约有效期：领取或续租时刻起 30 秒，到期时刻左闭右开。 */
    static final long LEASE_TTL_SECONDS = 30;

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final LeaseRepository leaseRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       LeaseRepository leaseRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.leaseRepository = leaseRepository;
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
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            // 已进入租约模式的任务不得再走原回执入口，防止旧代理绕过 agentId/token 校验
            if (leaseRepository.findByTaskIdForUpdate(taskId).isPresent()) {
                throw ApiException.conflict("LEASE_MODE_REQUIRED",
                        "任务已进入租约模式，须携带 agentId 与 token 走租约回执");
            }
            return switch (task.status()) {
                case PENDING -> completePending(task, lockedOrder, request.result());
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

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 领取（或到期接管）执行租约：
     * 首次领取得到 token=1 与当前时刻后 30 秒到期；有效期左闭右开，有效租约不能被其他代理抢占；
     * 到期后任何代理都可领取新 token（序号加一）；SUCCESS/FAILED 一次终结后不能再领取。
     * 本人对自己未到期租约重复领取，返回首次租约快照（幂等展示，不恢复已失效租约的有效性）。
     */
    public LeaseView acquireLease(long taskId, LeaseAcquireRequest request) {
        String fingerprint = String.join("|", "task.lease.acquire", String.valueOf(taskId), request.agentId());
        return idempotency.execute(request.requestId(), "task.lease.acquire", fingerprint, () -> {
            Instant now = Instant.now(clock);
            LockedTask locked = lockTaskAndOrder(taskId);
            RolloutTask task = locked.task();
            if (task.status() == TaskStatus.CANCELLED) {
                throw ApiException.conflict("TASK_CANCELLED", "任务已取消，不能领取租约");
            }
            Optional<TaskLease> existing = leaseRepository.findByTaskIdForUpdate(taskId);
            if (existing.isEmpty()) {
                if (task.status() != TaskStatus.PENDING) {
                    throw ApiException.conflict("TASK_TERMINATED",
                            "任务已一次终结为 " + task.firstResult() + "，不能再领取租约");
                }
                String expiresAt = now.plusSeconds(LEASE_TTL_SECONDS).toString();
                leaseRepository.insert(taskId, request.agentId(), expiresAt);
                return LeaseView.of(taskId, leaseRepository.findByTaskIdForUpdate(taskId).orElseThrow());
            }
            TaskLease lease = existing.get();
            if (lease.terminal()) {
                throw ApiException.conflict("TASK_TERMINATED",
                        "任务已一次终结为 " + task.firstResult() + "，不能再领取租约");
            }
            if (now.isBefore(Instant.parse(lease.expiresAtUtc()))) {
                if (lease.currentAgentId().equals(request.agentId())) {
                    // 本人重放：返回首次租约快照，但不改变其到期时刻，即不恢复有效性
                    return LeaseView.of(taskId, lease);
                }
                throw ApiException.conflict("LEASE_HELD_BY_OTHER",
                        "租约在有效期内，当前持有人: " + lease.currentAgentId());
            }
            // 到期接管：令牌加一，原 token 即刻失效
            String expiresAt = now.plusSeconds(LEASE_TTL_SECONDS).toString();
            if (leaseRepository.takeOverExpired(taskId, request.agentId(), expiresAt) != 1) {
                throw ApiException.conflict("TASK_TERMINATED", "任务已终结，不能接管租约");
            }
            return LeaseView.of(taskId, leaseRepository.findByTaskIdForUpdate(taskId).orElseThrow());
        }, LeaseView.class);
    }

    /**
     * 续租：须本人（agentId）凭当前 token 且租约未到期，期限改为当前时刻后 30 秒。
     * CANCELLED 任务或已终结租约不允许续租；到期、旧代理或非持有人均 409。
     */
    public LeaseView renewLease(long taskId, LeaseRenewRequest request) {
        String fingerprint = String.join("|", "task.lease.renew", String.valueOf(taskId),
                request.agentId(), String.valueOf(request.token()));
        return idempotency.execute(request.requestId(), "task.lease.renew", fingerprint, () -> {
            Instant now = Instant.now(clock);
            LockedTask locked = lockTaskAndOrder(taskId);
            TaskLease lease = leaseRepository.findByTaskIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.conflict("LEASE_NOT_FOUND", "任务尚未领取租约，不能续租"));
            if (locked.task().status() == TaskStatus.CANCELLED || lease.terminal()) {
                throw ApiException.conflict("LEASE_NOT_RENEWABLE", "任务已终结或已取消，不能续租");
            }
            if (!lease.currentAgentId().equals(request.agentId()) || lease.currentToken() != request.token()) {
                throw ApiException.conflict("LEASE_NOT_HELD_BY_CALLER",
                        "续租须由当前持有人凭当前 token 申请");
            }
            if (!now.isBefore(Instant.parse(lease.expiresAtUtc()))) {
                throw ApiException.conflict("LEASE_EXPIRED", "租约已到期，不能续租，请重新领取");
            }
            String expiresAt = now.plusSeconds(LEASE_TTL_SECONDS).toString();
            leaseRepository.renew(taskId, request.agentId(), request.token(), expiresAt);
            return LeaseView.of(taskId, leaseRepository.findByTaskIdForUpdate(taskId).orElseThrow());
        }, LeaseView.class);
    }

    /**
     * 租约模式回执：必须携带 agentId 与 token。到期、旧代理（token 已被接管）或非持有人一律 409，
     * 且不改动设备版本与监控统计。SUCCESS/FAILED 一次终结，冻结实际代理与 token；
     * 同一 token 的终结至多发生一次，仅首次终结计入监控样本。原暂停规则保持：既有任务回执不受影响。
     */
    public TaskView leaseReceipt(long taskId, LeaseReceiptRequest request) {
        String fingerprint = String.join("|", "task.lease.receipt", String.valueOf(taskId),
                request.agentId(), String.valueOf(request.token()), request.result().name());
        return idempotency.execute(request.requestId(), "task.lease.receipt", fingerprint, () -> {
            Instant now = Instant.now(clock);
            LockedTask locked = lockTaskAndOrder(taskId);
            TaskLease lease = leaseRepository.findByTaskIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.conflict("LEASE_NOT_FOUND", "任务尚未领取租约"));
            if (lease.terminal()) {
                // 冻结后任何代理、任何 token 的回执都不再受理，同一 token 终结至多一次
                throw ApiException.conflict("TASK_TERMINATED",
                        "任务已终结为 " + locked.task().firstResult() + "，不能再次回执");
            }
            boolean holder = lease.currentAgentId().equals(request.agentId())
                    && lease.currentToken() == request.token()
                    && now.isBefore(Instant.parse(lease.expiresAtUtc()));
            if (!holder) {
                throw ApiException.conflict("LEASE_INVALID",
                        "租约无效、已到期或不属于该代理，回执被拒绝，设备与统计不变");
            }
            RolloutTask task = locked.task();
            return switch (task.status()) {
                case PENDING -> {
                    TaskView view = completePending(task, locked.order(), request.result());
                    leaseRepository.markTerminal(taskId, request.agentId(), request.token());
                    yield view;
                }
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_TERMINATED",
                        "任务已终结为 " + task.firstResult() + "，不能再次回执");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * 查询租约当前快照（只读，不自动领取、不延期）。未进入租约模式的任务 409。
     */
    public LeaseView getLease(long taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        TaskLease lease = leaseRepository.findByTaskId(taskId)
                .orElseThrow(() -> ApiException.conflict("LEASE_NOT_FOUND", "任务尚未领取租约"));
        return LeaseView.of(taskId, lease);
    }

    /**
     * 统一加锁顺序：先发布单行锁，再任务行锁，使接管、回执、取消、续租串行校验、提交顺序一致。
     */
    private LockedTask lockTaskAndOrder(long taskId) {
        RolloutTask snapshot = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return new LockedTask(task, order);
    }

    /**
     * 首次终结任务：置任务终态、累计当前监控轮次统计、成功时更新设备版本、必要时同事务暂停。
     * 仅在任务为 PENDING 时调用，调用方持有发布单与任务行锁，保证同一任务至多首次终结计样本。
     */
    private TaskView completePending(RolloutTask task, ReleaseOrder lockedOrder, ReceiptResult result) {
        taskRepository.complete(task.id(), result);
        releaseRepository.incrementRoundStats(task.releaseId(), result);
        if (result == ReceiptResult.SUCCESS) {
            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
        }
        ReleaseOrder updated = releaseRepository.findById(task.releaseId()).orElseThrow();
        pauseIfThresholdReached(updated, task.id());
        return TaskView.of(taskRepository.findById(task.id()).orElseThrow(), updated);
    }

    private record LockedTask(RolloutTask task, ReleaseOrder order) {
    }
}
