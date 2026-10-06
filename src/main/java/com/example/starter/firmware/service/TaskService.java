package com.example.starter.firmware.service;

import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.StopConfirmRequest;
import com.example.starter.firmware.api.TaskCancelRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskStartRequest;
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
import com.example.starter.firmware.repo.SlotRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放任务：设备拉取、显式开始、回执、取消请求与停止确认。
 * 与取消、恢复并发时统一先锁发布单行，再锁任务行，最后操作安装槽，形成一致提交顺序。
 * 仅 INSTALLING 受理普通 SUCCESS/FAILED 回执；CANCEL_REQUESTED 只接受同代次停止确认。
 * 设备独占安装槽由 device_install_slot 主键保证，开始占槽、终态释放，取消待确认期间不释放。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final SlotRepository slotRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       SlotRepository slotRepository, DeviceService deviceService,
                       ReleaseService releaseService, IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.slotRepository = slotRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 或已取消时不创建新任务，已有任务仍可查看与回执。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = "op=task.pull|device=" + deviceId;
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
     * 显式开始：PENDING 转 INSTALLING 并占该设备独占安装槽；旧槽未释放则 422。
     * 开始成功后由回执或停止路径终结任务并释放槽。
     */
    public TaskView start(long taskId, TaskStartRequest request) {
        String fingerprint = "op=task.start|task=" + taskId + "|operator=" + request.operator()
                + "|expectedVersion=" + request.expectedVersion();
        return idempotency.execute(request.requestId(), "task.start", fingerprint, () -> {
            LockedTask locked = lockTask(taskId);
            requireVersion(locked.order(), request.expectedVersion());
            if (locked.task().status() != TaskStatus.PENDING) {
                throw ApiException.conflict("TASK_NOT_PENDING",
                        "任务状态为 " + locked.task().status() + "，仅 PENDING 可开始");
            }
            acquireSlot(locked.task());
            if (taskRepository.start(taskId) != 1) {
                throw new IllegalStateException("任务开始迁移未生效: " + taskId);
            }
            return TaskView.of(findTask(taskId), locked.order());
        }, TaskView.class);
    }

    /**
     * 任务取消请求：INSTALLING 转 CANCEL_REQUESTED 并生成取消代次（仍占槽，等待停止确认）；
     * 未开始的 PENDING 任务直接取消为 CANCELLED（不占槽）。取消不计失败率样本。
     */
    public TaskView cancelTask(long taskId, TaskCancelRequest request) {
        String fingerprint = "op=task.cancel|task=" + taskId + "|operator=" + request.operator()
                + "|expectedVersion=" + request.expectedVersion();
        return idempotency.execute(request.requestId(), "task.cancel", fingerprint, () -> {
            LockedTask locked = lockTask(taskId);
            requireVersion(locked.order(), request.expectedVersion());
            return switch (locked.task().status()) {
                case PENDING -> {
                    taskRepository.cancelPendingTask(taskId);
                    yield TaskView.of(findTask(taskId), locked.order());
                }
                case INSTALLING -> {
                    if (taskRepository.requestCancel(taskId, request.operator()) != 1) {
                        throw new IllegalStateException("任务取消请求迁移未生效: " + taskId);
                    }
                    yield TaskView.of(findTask(taskId), locked.order());
                }
                case CANCEL_REQUESTED -> throw ApiException.conflict("TASK_CANCEL_ALREADY_REQUESTED",
                        "任务已处于取消待确认，代次: " + locked.task().cancelGeneration());
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消");
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_FINISHED",
                        "任务已终结为 " + locked.task().status() + "，不能取消");
            };
        }, TaskView.class);
    }

    /**
     * 停止确认：仅 CANCEL_REQUESTED 且代次一致时转 CANCELLED 并释放设备独占安装槽；
     * 代次不符 409。确认后旧任务的成功回执一律拒绝，不再更新设备版本。
     */
    public TaskView confirmStop(long taskId, StopConfirmRequest request) {
        String fingerprint = "op=task.stopConfirm|task=" + taskId + "|operator=" + request.operator()
                + "|expectedVersion=" + request.expectedVersion() + "|generation=" + request.generation();
        return idempotency.execute(request.requestId(), "task.stopConfirm", fingerprint, () -> {
            LockedTask locked = lockTask(taskId);
            requireVersion(locked.order(), request.expectedVersion());
            if (locked.task().status() != TaskStatus.CANCEL_REQUESTED) {
                throw ApiException.conflict("TASK_NOT_CANCEL_REQUESTED",
                        "任务状态为 " + locked.task().status() + "，仅取消待确认可停止确认");
            }
            if (taskRepository.confirmStop(taskId, request.generation()) != 1) {
                throw ApiException.conflict("CANCEL_GENERATION_MISMATCH",
                        "停止确认代次 " + request.generation() + " 与当前取消代次 "
                                + locked.task().cancelGeneration() + " 不一致");
            }
            releaseSlot(locked.task());
            return TaskView.of(findTask(taskId), locked.order());
        }, TaskView.class);
    }

    /**
     * 回执：仅 INSTALLING 受理普通 SUCCESS/FAILED；兼容旧调用方，PENDING 任务在同一事务内
     * 先占槽开始（INSTALLING）再终结，不绕过设备独占安装槽。
     * 首次终结计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；同结果重复成功（不重复计数），
     * 改结果 409；CANCEL_REQUESTED 拒绝安装回执 409（只接受停止确认）；已取消任务回执 409。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = "op=task.receipt|task=" + taskId + "|result=" + request.result().name();
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    // 兼容路径：同事务占槽开始再终结，槽不绕过、不残留
                    acquireSlot(task);
                    if (taskRepository.start(taskId) != 1) {
                        throw new IllegalStateException("任务开始迁移未生效: " + taskId);
                    }
                    yield completeInstalling(taskId, task, lockedOrder, request.result());
                }
                case INSTALLING -> completeInstalling(taskId, task, lockedOrder, request.result());
                case CANCEL_REQUESTED -> throw ApiException.conflict("TASK_CANCEL_REQUESTED",
                        "任务取消待确认，拒绝安装回执，只接受停止确认");
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
     * INSTALLING 任务终结：状态迁移、轮次统计、设备版本更新与槽释放同事务完成，仅生效一次。
     */
    private TaskView completeInstalling(long taskId, RolloutTask task, ReleaseOrder lockedOrder,
                                        ReceiptResult result) {
        if (taskRepository.complete(taskId, result) != 1) {
            throw new IllegalStateException("任务终结迁移未生效: " + taskId);
        }
        releaseRepository.incrementRoundStats(lockedOrder.id(), result);
        if (result == ReceiptResult.SUCCESS) {
            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
        }
        releaseSlot(task);
        ReleaseOrder updated = releaseRepository.findById(lockedOrder.id()).orElseThrow();
        pauseIfThresholdReached(updated, taskId);
        return TaskView.of(findTask(taskId), updated);
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
     * 占设备独占安装槽；旧槽未释放（含取消待确认任务）则 422，不暴露唯一键错误。
     */
    private void acquireSlot(RolloutTask task) {
        if (!slotRepository.tryAcquire(task.deviceId(), task.id())) {
            Long holder = slotRepository.findHolder(task.deviceId()).orElse(null);
            throw ApiException.unprocessable("DEVICE_SLOT_BUSY",
                    "设备 " + task.deviceId() + " 的安装槽被任务 " + holder + " 占用，旧槽未释放不能开始新任务");
        }
    }

    /**
     * 释放设备独占安装槽；仅槽由该任务持有时生效，重复释放不影响他人槽。
     */
    private void releaseSlot(RolloutTask task) {
        slotRepository.release(task.deviceId(), task.id());
    }

    /**
     * 锁发布单行与任务行（固定顺序），返回快照。
     */
    private LockedTask lockTask(long taskId) {
        RolloutTask snapshot = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return new LockedTask(order, task);
    }

    private void requireVersion(ReleaseOrder order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion 与当前版本不一致: " + order.version());
        }
    }

    private RolloutTask findTask(long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务迁移后读取失败: " + taskId));
    }

    private record LockedTask(ReleaseOrder order, RolloutTask task) {
    }

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }
}
