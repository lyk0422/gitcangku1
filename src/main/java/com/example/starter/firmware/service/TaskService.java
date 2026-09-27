package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BlockListResponse;
import com.example.starter.firmware.api.ModuleBlockView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ModuleBlockItem;
import com.example.starter.firmware.domain.ModuleBlockRecord;
import com.example.starter.firmware.domain.ModuleVersions;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.BlockRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投放任务：设备拉取与回执。与取消、恢复并发时统一先锁发布单行，再操作任务，形成一致提交顺序。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 * 拉取在既有门禁（型号、版本、分桶、ACTIVE）通过后逐项校验目标固件的完整依赖集合：
 * 缺失或低版本且未被豁免的模块按名升序返回 MODULE_BLOCKED，不创建任务、不计入失败率，
 * 并固化阻断时的设备模块摘要、矩阵版本与豁免版本。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final BlockRepository blockRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final MatrixService matrixService;
    private final DeviceModuleService deviceModuleService;
    private final ExemptionService exemptionService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       BlockRepository blockRepository, DeviceService deviceService,
                       ReleaseService releaseService, MatrixService matrixService,
                       DeviceModuleService deviceModuleService, ExemptionService exemptionService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.blockRepository = blockRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.matrixService = matrixService;
        this.deviceModuleService = deviceModuleService;
        this.exemptionService = exemptionService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE
     * 且模块兼容校验通过时创建。PAUSED 时不创建新任务，已有任务仍可查看与回执。
     * 发布单行锁之后锁设备行（与回执一致的 release→device 顺序），
     * 保证固化的模块摘要与评估用清单来自同一上报版本。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return PullResponse.empty();
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return PullResponse.ofTask(TaskView.of(existing.get(), order));
            }
            Device lockedDevice = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (order.status() != ReleaseStatus.ACTIVE
                    || !lockedDevice.currentVersion().equals(order.fromVersion())
                    || lockedDevice.bucketNo() >= order.ratio()) {
                return PullResponse.empty();
            }
            MatrixService.MatrixSnapshot snapshot = matrixService.snapshotForPull(order.toVersion());
            int exemptionVersion = order.exemptionVersion();
            ModuleBlockView block = checkModuleCompatibility(order, lockedDevice, snapshot,
                    exemptionVersion);
            if (block != null) {
                return PullResponse.ofBlock(block);
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId, lockedDevice.moduleDigest(),
                        snapshot.version(), exemptionVersion);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return PullResponse.ofTask(TaskView.of(task, order));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return PullResponse.ofTask(TaskView.of(task, order));
        }, PullResponse.class);
    }

    /**
     * 模块兼容校验：逐项检查目标固件的完整依赖集合，未被豁免的缺失或低版本模块
     * 固化阻断记录后返回 MODULE_BLOCKED 视图；全部满足或无依赖要求时返回 null。
     */
    private ModuleBlockView checkModuleCompatibility(ReleaseOrder order, Device device,
                                                     MatrixService.MatrixSnapshot snapshot,
                                                     int exemptionVersion) {
        Map<String, String> required = snapshot.closure();
        if (required.isEmpty()) {
            return null;
        }
        int matrixVersion = snapshot.version();
        Map<String, String> deviceModules = deviceModuleService.currentModuleMap(device.deviceId());
        Set<String> exempted = exemptionService.activeExemptedModules(order.id(), exemptionVersion,
                device.deviceGroup(), Instant.now(clock));
        List<ModuleBlockItem> missing = required.entrySet().stream()
                .filter(entry -> !exempted.contains(entry.getKey()))
                .filter(entry -> {
                    String deviceVersion = deviceModules.get(entry.getKey());
                    return deviceVersion == null
                            || !ModuleVersions.satisfies(deviceVersion, entry.getValue());
                })
                .map(entry -> new ModuleBlockItem(entry.getKey(), deviceModules.get(entry.getKey()),
                        entry.getValue()))
                .sorted(Comparator.comparing(ModuleBlockItem::module))
                .toList();
        if (missing.isEmpty()) {
            return null;
        }
        String blockedAtUtc = Instant.now(clock).toString();
        long blockId = blockRepository.insertRecord(order.id(), device.deviceId(), matrixVersion,
                exemptionVersion, device.moduleDigest(), blockedAtUtc);
        for (ModuleBlockItem item : missing) {
            blockRepository.insertItem(blockId, item);
        }
        ModuleBlockRecord record = new ModuleBlockRecord(blockId, order.id(), device.deviceId(),
                matrixVersion, exemptionVersion, device.moduleDigest(), blockedAtUtc);
        return ModuleBlockView.of(record, missing);
    }

    /**
     * 回执：首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备当前版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     * 设备模块清单只能由设备上报改变，回执成功不虚构模块升级。
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
     * 发布单模块阻断诊断列表（只读，不改变状态），按阻断记录ID升序。
     */
    public BlockListResponse listBlocks(long releaseId) {
        releaseService.findOrder(releaseId);
        List<ModuleBlockView> blocks = blockRepository.findRecordsByRelease(releaseId).stream()
                .map(record -> ModuleBlockView.of(record, blockRepository.findItems(record.id())))
                .toList();
        return new BlockListResponse(releaseId, blocks);
    }
}
