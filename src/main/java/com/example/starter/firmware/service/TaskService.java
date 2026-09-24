package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeferStateView;
import com.example.starter.firmware.api.DeferSummaryView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.MaintenanceWindow;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeferStateRepository;
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
 * 投放任务：设备拉取与回执。与取消、恢复、窗口修订并发时统一按“发布单行锁 → 设备行锁”顺序加锁，
 * 与回执（发布单 → 任务 → 设备更新）同序，避免死锁；只锁设备行的窗口修订不可能形成环。
 * 开启维护窗口后，窗口外拉取仅在命中目标的设备上顺延（DEFERRED）：不下发任务、
 * 不改任务状态、不计入失败率样本，只累加顺延统计；PAUSED 与顺延返回可区分结果。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeferStateRepository deferStateRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       DeferStateRepository deferStateRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deferStateRepository = deferStateRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * 开启 respectMaintenanceWindow 时，would-be 下发设备必须处于本地维护窗口内：
     * 窗口外 DEFERRED 并累加顺延统计，窗口内但 PAUSED 返回 RELEASE_PAUSED（两者可区分）。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            // 先读取型号定位发布单（不锁），再按“发布单行锁 → 设备行锁”顺序加锁
            Device device = deviceService.findDevice(deviceId);
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return PullResponse.noMatch("NO_ACTIVE_RELEASE");
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            Device lockedDevice = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                // 已有任务（PENDING/成功/失败/已取消）一律直接返回，窗口不拦截查看与回执
                return PullResponse.dispatched(TaskView.of(existing.get(), order));
            }
            boolean matchesTarget = lockedDevice.currentVersion().equals(order.fromVersion())
                    && lockedDevice.bucketNo() < order.ratio();
            if (!matchesTarget) {
                // 当前版本或分桶不匹配：与窗口、暂停均无关的不投放
                return PullResponse.noMatch("NOT_ELIGIBLE");
            }
            if (order.status() == ReleaseStatus.CANCELLED) {
                // 定位发布单后、加锁前被并发取消：无投放目标，不顺延
                return PullResponse.noMatch("RELEASE_NOT_ACTIVE");
            }
            if (order.respectMaintenanceWindow()) {
                MaintenanceWindow window = new MaintenanceWindow(
                        lockedDevice.windowStartMinute(), lockedDevice.windowEndMinute());
                Instant now = Instant.now(clock);
                if (!window.contains(now, lockedDevice.utcOffsetMinutes())) {
                    // 窗口外：无论 ACTIVE 还是 PAUSED 都顺延，不下发、不改任务状态、不计样本，仅累加顺延统计
                    deferStateRepository.increment(order.id(), deviceId, now.toString());
                    return PullResponse.deferred(window.nextStart(now, lockedDevice.utcOffsetMinutes()).toString());
                }
            }
            // 命中目标且在窗口内（或未开启窗口）：PAUSED 与顺延可区分，其余状态正常下发
            if (order.status() == ReleaseStatus.PAUSED) {
                return PullResponse.paused();
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return PullResponse.dispatched(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return PullResponse.dispatched(TaskView.of(task, order));
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
            return switch (task.status()) {
                case PENDING -> {
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
     * 发布单下全部任务顺延统计（只读），按设备ID稳定排序。
     */
    public List<DeferStateView> listDefers(long releaseId) {
        releaseService.findOrder(releaseId);
        return deferStateRepository.findByRelease(releaseId).stream()
                .map(DeferStateView::of).toList();
    }

    /**
     * 发布单顺延汇总（只读）：被顺延任务总数、全部顺延次数合计，明细按设备ID稳定排序。
     */
    public DeferSummaryView deferSummary(long releaseId) {
        releaseService.findOrder(releaseId);
        List<DeferStateView> defers = listDefers(releaseId);
        int total = defers.stream().mapToInt(DeferStateView::deferCount).sum();
        return new DeferSummaryView(releaseId, defers.size(), total, defers);
    }

    /**
     * 单个任务（同发布单同设备）的顺延统计（只读）。
     */
    public DeferStateView getDefer(long releaseId, String deviceId) {
        releaseService.findOrder(releaseId);
        deviceService.findDevice(deviceId);
        return deferStateRepository.find(releaseId, deviceId)
                .map(DeferStateView::of)
                .orElseThrow(() -> ApiException.notFound("DEFER_STATE_NOT_FOUND",
                        "该发布单与设备尚无顺延记录: releaseId=" + releaseId + ", deviceId=" + deviceId));
    }
}
