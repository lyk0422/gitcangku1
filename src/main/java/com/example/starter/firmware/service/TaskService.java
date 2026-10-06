package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateInstallTaskRequest;
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
import java.util.Map;

/**
 * 投放任务：设备拉取与回执，安装握手任务的开始、取消请求与停止确认。
 * 与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * INSTALL 任务由 PENDING 显式开始后为 INSTALLING 并占设备独占安装槽，仅 INSTALLING 接受普通回执；
 * 取消请求转 CANCEL_REQUESTED 并生成取消代次（保留槽），同代次停止确认才 CANCELLED 并释放槽。
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
     * 设备安装槽被占用（INSTALLING/CANCEL_REQUESTED）时不绕过槽创建新任务，返回 422。
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
            var slotHolder = taskRepository.findSlotHolder(deviceId);
            if (slotHolder.isPresent()) {
                throw ApiException.unprocessable("SLOT_OCCUPIED",
                        "设备安装槽被任务 " + slotHolder.get().id() + " 占用，不能领取新任务");
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
     * 创建安装握手任务：发布单 ACTIVE 且设备满足投放条件时创建 INSTALL 类型 PENDING 任务（不占槽）。
     * 同设备同发布单已存在任务时直接返回已有任务。
     */
    public TaskView createInstallTask(long releaseId, CreateInstallTaskRequest request) {
        String fingerprint = idempotency.fingerprint("task.install.create", "release:" + releaseId,
                Map.of("deviceId", request.deviceId(), "operator", request.operator(),
                        "expectedVersion", request.expectedVersion()));
        return idempotency.execute(request.requestId(), "task.install.create", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            requireReleaseVersion(order, request.expectedVersion());
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能创建安装任务");
            }
            Device device = deviceService.findDevice(request.deviceId());
            var existing = taskRepository.findByReleaseAndDevice(releaseId, request.deviceId());
            if (existing.isPresent()) {
                return TaskView.of(existing.get(), order);
            }
            if (!device.model().equals(order.model())
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                throw ApiException.unprocessable("INSTALL_NOT_ELIGIBLE",
                        "设备不满足投放条件，不能创建安装任务: " + request.deviceId());
            }
            long taskId;
            try {
                taskId = taskRepository.insert(releaseId, request.deviceId(), TaskType.INSTALL);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(releaseId, request.deviceId())
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return TaskView.of(task, order);
            }
            return TaskView.of(taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败")), order);
        }, TaskView.class);
    }

    /**
     * 安装任务显式开始：PENDING 转 INSTALLING 并占设备独占安装槽；
     * 同设备旧槽未释放则其他新类型任务不能开始（422），并发占槽冲突由唯一索引兜底并同样转 422。
     */
    public TaskView start(long taskId, TaskStartRequest request) {
        String fingerprint = idempotency.fingerprint("task.start", "task:" + taskId,
                Map.of("operator", request.operator(), "expectedVersion", request.expectedVersion()));
        return idempotency.execute(request.requestId(), "task.start", fingerprint, () -> {
            LockedTask locked = lockTaskWithRelease(taskId);
            requireReleaseVersion(locked.order(), request.expectedVersion());
            RolloutTask task = locked.task();
            if (task.taskType() != TaskType.INSTALL) {
                throw ApiException.conflict("TASK_NOT_INSTALL_TYPE", "普通任务无需显式开始");
            }
            return switch (task.status()) {
                case PENDING -> {
                    var holder = taskRepository.findSlotHolder(task.deviceId());
                    if (holder.isPresent()) {
                        throw ApiException.unprocessable("SLOT_OCCUPIED",
                                "设备安装槽被任务 " + holder.get().id() + " 占用，不能开始新安装");
                    }
                    try {
                        taskRepository.startInstall(taskId);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.unprocessable("SLOT_OCCUPIED", "设备安装槽被并发占用，不能开始新安装");
                    }
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), locked.order());
                }
                case INSTALLING -> TaskView.of(task, locked.order());
                default -> throw ApiException.conflict("TASK_NOT_STARTABLE",
                        "任务状态为 " + task.status() + "，不能开始");
            };
        }, TaskView.class);
    }

    /**
     * 任务取消请求：PENDING 直接取消；INSTALLING 转 CANCEL_REQUESTED 并生成新取消代次（保留槽）；
     * CANCEL_REQUESTED 再次请求仅递增代次，旧代次停止确认随之失效。取消不计失败率样本。
     */
    public TaskView cancelRequest(long taskId, TaskCancelRequest request) {
        String fingerprint = idempotency.fingerprint("task.cancel.request", "task:" + taskId,
                Map.of("operator", request.operator(), "expectedVersion", request.expectedVersion()));
        return idempotency.execute(request.requestId(), "task.cancel.request", fingerprint, () -> {
            LockedTask locked = lockTaskWithRelease(taskId);
            requireReleaseVersion(locked.order(), request.expectedVersion());
            RolloutTask task = locked.task();
            switch (task.status()) {
                case PENDING -> taskRepository.cancelPending(taskId);
                case INSTALLING -> taskRepository.requestCancel(taskId, locked.order().monitorRound());
                case CANCEL_REQUESTED -> taskRepository.renewCancel(taskId, locked.order().monitorRound());
                default -> throw ApiException.conflict("TASK_NOT_CANCELLABLE",
                        "任务状态为 " + task.status() + "，不能取消");
            }
            return TaskView.of(taskRepository.findById(taskId).orElseThrow(), locked.order());
        }, TaskView.class);
    }

    /**
     * 停止确认：仅同代次的 CANCEL_REQUESTED 任务转 CANCELLED 并释放安装槽；
     * 不更新设备版本、不计失败率样本。
     */
    public TaskView stopConfirm(long taskId, StopConfirmRequest request) {
        String fingerprint = idempotency.fingerprint("task.stop.confirm", "task:" + taskId,
                Map.of("operator", request.operator(), "expectedVersion", request.expectedVersion(),
                        "generation", request.generation()));
        return idempotency.execute(request.requestId(), "task.stop.confirm", fingerprint, () -> {
            LockedTask locked = lockTaskWithRelease(taskId);
            requireReleaseVersion(locked.order(), request.expectedVersion());
            RolloutTask task = locked.task();
            if (task.status() != TaskStatus.CANCEL_REQUESTED) {
                throw ApiException.conflict("TASK_NOT_CANCEL_REQUESTED",
                        "任务状态为 " + task.status() + "，无待确认取消");
            }
            if (task.cancelGeneration() != request.generation()) {
                throw ApiException.conflict("CANCEL_GENERATION_MISMATCH",
                        "取消代次不一致，当前代次: " + task.cancelGeneration());
            }
            taskRepository.confirmStop(taskId, request.generation());
            return TaskView.of(taskRepository.findById(taskId).orElseThrow(), locked.order());
        }, TaskView.class);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * INSTALL 任务仅 INSTALLING 接受普通回执；CANCEL_REQUESTED 拒绝回执（409），只接受停止确认。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            LockedTask locked = lockTaskWithRelease(taskId);
            RolloutTask task = locked.task();
            return switch (task.status()) {
                case PENDING -> {
                    if (task.taskType() == TaskType.INSTALL) {
                        throw ApiException.conflict("TASK_NOT_STARTED", "安装任务未显式开始，不受理回执");
                    }
                    yield completeTask(task, locked.order(), request.result());
                }
                case INSTALLING -> completeTask(task, locked.order(), request.result());
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield TaskView.of(task, locked.order());
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCEL_REQUESTED -> throw ApiException.conflict("TASK_CANCEL_REQUESTED",
                        "任务取消待确认，只接受停止确认");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * 首次回执终结任务：成功前校验设备槽不被其他任务占用（旧入口不绕过槽），
     * 终结与释放槽、统计累计、设备版本更新在同一事务原子提交。
     */
    private TaskView completeTask(RolloutTask task, ReleaseOrder lockedOrder, ReceiptResult result) {
        if (result == ReceiptResult.SUCCESS) {
            var holder = taskRepository.findSlotHolder(task.deviceId());
            if (holder.isPresent() && holder.get().id() != task.id()) {
                throw ApiException.conflict("SLOT_OCCUPIED",
                        "设备安装槽被任务 " + holder.get().id() + " 占用，不能完成安装");
            }
        }
        taskRepository.complete(task.id(), result);
        releaseRepository.incrementRoundStats(task.releaseId(), result);
        if (result == ReceiptResult.SUCCESS) {
            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
        }
        ReleaseOrder updated = releaseRepository.findById(task.releaseId()).orElseThrow();
        pauseIfThresholdReached(updated, task.id());
        return TaskView.of(taskRepository.findById(task.id()).orElseThrow(), updated);
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
     * 按发布单行锁 + 任务行锁的一致顺序锁定任务及其发布单。
     */
    private LockedTask lockTaskWithRelease(long taskId) {
        RolloutTask snapshot = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return new LockedTask(order, task);
    }

    private void requireReleaseVersion(ReleaseOrder order, int expectedVersion) {
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion 与当前版本不一致: " + order.version());
        }
    }

    private record LockedTask(ReleaseOrder order, RolloutTask task) {
    }
}
