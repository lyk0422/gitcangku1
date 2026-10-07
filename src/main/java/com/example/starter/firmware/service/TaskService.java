package com.example.starter.firmware.service;

import com.example.starter.firmware.api.LeaseRequest;
import com.example.starter.firmware.api.LeaseView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RenewLeaseRequest;
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
 * 投放任务：设备拉取、执行租约（领取/续租）与回执。
 *
 * <p>执行租约：代理拉取到既有任务后以非空 agentId 显式领取，得到从 1 递增的 token 及
 * 服务器 UTC 当前时刻后 30 秒到期时刻；有效期左闭右开，有效期内不能被其他代理抢占，
 * 到期后任意代理可领取递增后的新 token。续租须本人持当前 token 且未到期。</p>
 *
 * <p>接管、回执、取消、续租均在任务行锁（回执与取消还先持有发布单行锁）内串行校验；
 * 同一 token 对任务至多终结一次，只有首次终结计入监控样本。成功回执冻结实际代理与 token；
 * 旧代理在被接管后的成功/续租/领取重放不能恢复其有效性。查询不自动领取，时钟可注入，无后台进程。</p>
 */
@Service
public class TaskService {

    /**
     * 租约有效期：领取或成功续租后，自服务器当前时刻起 30 秒。
     */
    static final int LEASE_TTL_SECONDS = 30;

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
     * 设备拉取：已存在任务直接返回（不自动领取租约）；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
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
     * 领取执行租约：
     * <ul>
     *   <li>从未领取：token=1，到期时刻=now+30s；</li>
     *   <li>当前租约仍有效（左闭右开）：只有本人（同 agentId）可重领，返回当前租约；其他代理 409 LEASE_HELD_BY_OTHER；</li>
     *   <li>当前租约已到期：任意代理可领取，token 在历史最新值上加一；</li>
     *   <li>SUCCESS/FAILED 已终结：不能再领取，409 TASK_TERMINATED；CANCELLED：409 TASK_CANCELLED。</li>
     * </ul>
     */
    public LeaseView acquire(long taskId, LeaseRequest request) {
        String fingerprint = String.join("|", "task.lease.acquire", String.valueOf(taskId), request.agentId());
        return idempotency.execute(request.requestId(), "task.lease.acquire", fingerprint, () -> {
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            rejectIfTerminal(task);
            Instant now = Instant.now(clock);
            if (task.leaseToken() != null && !now.isBefore(task.leaseExpiresAt())) {
                int newToken = task.leaseToken() + 1;
                Instant expiresAt = now.plusSeconds(LEASE_TTL_SECONDS);
                taskRepository.grantLease(taskId, request.agentId(), newToken, expiresAt);
                return new LeaseView(taskId, request.agentId(), newToken, expiresAt.toString());
            }
            if (task.leaseToken() != null && !task.leaseAgentId().equals(request.agentId())) {
                throw ApiException.conflict("LEASE_HELD_BY_OTHER",
                        "有效租约由代理 " + task.leaseAgentId() + " 持有，代理 " + request.agentId() + " 不能抢占");
            }
            if (task.leaseToken() != null) {
                return new LeaseView(taskId, task.leaseAgentId(), task.leaseToken(),
                        task.leaseExpiresAt().toString());
            }
            Instant expiresAt = now.plusSeconds(LEASE_TTL_SECONDS);
            taskRepository.grantLease(taskId, request.agentId(), 1, expiresAt);
            return new LeaseView(taskId, request.agentId(), 1, expiresAt.toString());
        }, LeaseView.class);
    }

    /**
     * 续租：须本人（agentId 与当前持有人一致）且 token 为当前令牌，且租约未到期；到期时刻改为 now+30s。
     * CANCELLED 任务不允许续租；SUCCESS/FAILED 已终结亦不允许。
     */
    public LeaseView renew(long taskId, RenewLeaseRequest request) {
        String fingerprint = String.join("|", "task.lease.renew", String.valueOf(taskId),
                request.agentId(), String.valueOf(request.token()));
        return idempotency.execute(request.requestId(), "task.lease.renew", fingerprint, () -> {
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            rejectIfTerminal(task);
            if (task.leaseToken() == null) {
                throw ApiException.conflict("LEASE_NOT_FOUND", "任务尚未被领取，无租约可续");
            }
            Instant now = Instant.now(clock);
            if (!now.isBefore(task.leaseExpiresAt())) {
                throw ApiException.conflict("LEASE_EXPIRED",
                        "租约已于 " + task.leaseExpiresAt() + " 到期，不能续租，请重新领取");
            }
            if (!task.leaseAgentId().equals(request.agentId()) || task.leaseToken() != request.token()) {
                throw ApiException.conflict("LEASE_HOLDER_MISMATCH",
                        "续租代理或令牌与当前有效租约不一致");
            }
            Instant expiresAt = now.plusSeconds(LEASE_TTL_SECONDS);
            taskRepository.renewLease(taskId, expiresAt);
            return new LeaseView(taskId, request.agentId(), request.token(), expiresAt.toString());
        }, LeaseView.class);
    }

    private void rejectIfTerminal(RolloutTask task) {
        switch (task.status()) {
            case SUCCESS, FAILED -> throw ApiException.conflict("TASK_TERMINATED",
                    "任务已终结为 " + task.firstResult() + "，不能领取或续租");
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，租约不再受理");
            default -> {
            }
        }
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复回执（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     *
     * <p>租约模式：任务一旦被领取过，回执必须携带与当前有效租约一致的 agentId/token；
     * 租约到期、令牌属于被接管的旧代理、或非持有人均 409 且不改设备与统计。旧回执入口不能绕过该模式。</p>
     *
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name(),
                request.agentId() == null ? "" : request.agentId(),
                request.token() == null ? "" : String.valueOf(request.token()));
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    String agentId = validateLeaseForReceipt(task, request);
                    Integer frozenToken = task.leaseToken();
                    taskRepository.complete(taskId, request.result(), agentId, frozenToken);
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                    }
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    pauseIfThresholdReached(updated, taskId);
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                case SUCCESS, FAILED -> {
                    validateReplayForTerminal(task, request);
                    yield TaskView.of(task, lockedOrder);
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * PENDING 任务的租约校验，通过则返回实际持有人代理ID。
     * 从未领取时仅允许旧入口（无凭据）；一旦进入租约模式，旧入口与到期/旧代/非持有人凭据一律 409。
     */
    private String validateLeaseForReceipt(RolloutTask task, ReceiptRequest request) {
        if (task.leaseToken() == null) {
            if (request.hasLeaseCredentials()) {
                throw ApiException.conflict("LEASE_NOT_FOUND", "任务尚未被领取，无有效租约");
            }
            return null;
        }
        if (!request.hasLeaseCredentials()) {
            throw ApiException.conflict("LEASE_REQUIRED",
                    "任务已进入租约模式，回执必须携带有效的 agentId 与 token");
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(task.leaseExpiresAt())) {
            throw ApiException.conflict("LEASE_EXPIRED",
                    "租约已于 " + task.leaseExpiresAt() + " 到期，回执不再受理");
        }
        if (task.leaseToken() != request.token()) {
            throw ApiException.conflict("LEASE_SUPERSEDED",
                    "令牌 " + request.token() + " 已被新代理接管（当前令牌 " + task.leaseToken()
                            + "），旧代理不能写回安装结果");
        }
        if (!task.leaseAgentId().equals(request.agentId())) {
            throw ApiException.conflict("LEASE_HOLDER_MISMATCH",
                    "回执代理不是当前有效租约持有人，安装结果不予写回");
        }
        return request.agentId();
    }

    /**
     * 已终结任务的重复回执校验：旧入口只接受从未领取过的任务；租约模式只接受冻结的实际代理与令牌，
     * 且结果须与首次结果一致。重复回执不改设备与统计；改结果 409。
     */
    private void validateReplayForTerminal(RolloutTask task, ReceiptRequest request) {
        if (task.resultToken() == null) {
            if (request.hasLeaseCredentials()) {
                throw ApiException.conflict("LEASE_NOT_FOUND", "任务从未被领取，不存在租约凭据");
            }
        } else {
            if (!request.hasLeaseCredentials()) {
                throw ApiException.conflict("LEASE_REQUIRED",
                        "任务已进入租约模式，回执必须携带有效的 agentId 与 token");
            }
            if (!task.resultAgentId().equals(request.agentId()) || task.resultToken() != request.token()) {
                throw ApiException.conflict("LEASE_HOLDER_MISMATCH",
                        "终结代理与令牌已冻结，非实际终结代理的回执不予受理");
            }
        }
        if (task.firstResult() != request.result()) {
            throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                    "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
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
