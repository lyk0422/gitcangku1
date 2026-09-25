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
import com.example.starter.firmware.repo.RegionLimitRepository;
import com.example.starter.firmware.repo.RegionThrottleEventRepository;
import com.example.starter.firmware.repo.RegionWaitRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 区域限流判定、回执计数与上限配置修改同样由发布单行锁串行化，按事务提交顺序裁决。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final RegionLimitRepository regionLimitRepository;
    private final RegionWaitRepository regionWaitRepository;
    private final RegionThrottleEventRepository throttleEventRepository;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, DeviceService deviceService,
                       ReleaseService releaseService, IdempotencyService idempotency,
                       RegionLimitRepository regionLimitRepository,
                       RegionWaitRepository regionWaitRepository,
                       RegionThrottleEventRepository throttleEventRepository, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.regionLimitRepository = regionLimitRepository;
        this.regionWaitRepository = regionWaitRepository;
        this.throttleEventRepository = throttleEventRepository;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return PullResponse.none();
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return PullResponse.issued(TaskView.of(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return PullResponse.none();
            }
            PullResponse throttled = checkRegionThrottle(order, device);
            if (throttled != null) {
                return throttled;
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return PullResponse.issued(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return PullResponse.issued(TaskView.of(task, order));
        }, PullResponse.class);
    }

    /**
     * 回执：首次回执终结任务，仅 SUCCESS 更新设备当前版本；同结果重复成功，改结果 409；
     * 已取消任务的后到回执 409 且不更新设备版本。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    taskRepository.complete(taskId, request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(task.deviceId(), order.toVersion());
                    }
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), order);
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield TaskView.of(task, order);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 区域限流判定：返回非 null 表示被限流（不下发任务、不改变设备或任务状态）。
     * 须在持有发布单行锁的事务内调用，进行中计数与任务下发、回执终结按提交顺序一致。
     * 区域未配置上限时不限流；未曾被限流过的设备不排队，正常按比例规则参与。
     */
    private PullResponse checkRegionThrottle(ReleaseOrder order, Device device) {
        var limit = regionLimitRepository.findLimit(order.id(), device.region());
        if (limit.isEmpty()) {
            return null;
        }
        long inFlight = taskRepository.countInFlightByRegion(order.id(), device.region());
        long available = limit.get() - inFlight;
        if (available <= 0) {
            return throttle(order, device);
        }
        var wait = regionWaitRepository.find(order.id(), device.deviceId());
        if (wait.isEmpty()) {
            return null;
        }
        long ahead = regionWaitRepository.countAhead(order.id(), device.region(),
                wait.get().waitedAt(), device.deviceId());
        if (ahead >= available) {
            // 空位按等待时刻从早到晚释放，同刻按设备ID字典序；该设备前面仍有更早的等待者
            return throttle(order, device);
        }
        regionWaitRepository.delete(order.id(), device.deviceId());
        return null;
    }

    /**
     * 记录一次限流：刷新该设备的等待记录（保留最近一次限流时刻）并追加限流历史事件。
     */
    private PullResponse throttle(ReleaseOrder order, Device device) {
        LocalDateTime now = LocalDateTime.now(clock);
        regionWaitRepository.upsert(order.id(), device.deviceId(), device.region(), now);
        throttleEventRepository.append(order.id(), device.region(), device.deviceId(), now);
        return PullResponse.throttled();
    }
}
