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
import com.example.starter.firmware.repo.RegionThrottleRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 投放任务：设备拉取与回执。与取消、区域上限修改并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 区域限流：进行中计数取发布单在该区域的真实 PENDING 任务数，达到上限返回 THROTTLED，
 * 不下发任务、不计入失败率样本、不改变设备或任务状态；被限流设备写入等待记录，
 * 区域有空位时按最近一次限流时刻从早到晚（同时刻按设备标识字典序）决定谁先获得名额。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final RegionThrottleRepository regionThrottleRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, RegionThrottleRepository regionThrottleRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.regionThrottleRepository = regionThrottleRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时，
     * 再通过区域限流与公平排队判定后创建。THROTTLED 为瞬态结果，不占用幂等键。
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
                return PullResponse.dispatched(TaskView.of(existing.get(), order));
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
                return PullResponse.dispatched(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return PullResponse.dispatched(TaskView.of(task, order));
        }, PullResponse.class, response -> !response.isThrottled());
    }

    /**
     * 区域限流与公平排队判定：返回 null 表示可下发；否则写入等待记录与限流历史并返回 THROTTLED。
     * 调用方已持有发布单行锁，计数读取与任务插入、回执的状态变更串行，计数不会漂移。
     */
    private PullResponse checkRegionThrottle(ReleaseOrder order, Device device) {
        if (order.regionLimit() == null) {
            return null;
        }
        String region = device.region();
        int inFlight = regionThrottleRepository.countInFlight(order.id(), region);
        int available = order.regionLimit() - inFlight;
        List<RegionThrottleRepository.WaitEntry> waiting =
                regionThrottleRepository.findWaiting(order.id(), region);
        int myRank = 0;
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).deviceId().equals(device.deviceId())) {
                myRank = i + 1;
                break;
            }
        }
        if (myRank == 0) {
            // 未曾被限流过的设备不排队：有空位即按现有比例规则下发
            if (available < 1) {
                return throttle(order, device);
            }
            return null;
        }
        // 排队设备：名额按等待时刻从早到晚释放，排在前 available 名内才可获得
        if (myRank > available) {
            return throttle(order, device);
        }
        regionThrottleRepository.deleteWaiting(order.id(), device.deviceId());
        return null;
    }

    private PullResponse throttle(ReleaseOrder order, Device device) {
        regionThrottleRepository.upsertWaiting(order.id(), device.region(), device.deviceId());
        regionThrottleRepository.insertThrottleEvent(order.id(), device.region(), device.deviceId());
        return PullResponse.throttled();
    }

    /**
     * 回执：首次回执终结任务，仅 SUCCESS 更新设备当前版本；同结果重复成功，改结果 409；
     * 已取消任务的后到回执 409 且不更新设备版本。任务离开 PENDING 即释放区域进行中名额，
     * 重复回执不改变状态，不会重复减少计数。
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
}
