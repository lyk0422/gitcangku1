package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DispatchReleaseRequest;
import com.example.starter.firmware.api.DispatchResponse;
import com.example.starter.firmware.api.ExclusionView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.GateDecision;
import com.example.starter.firmware.domain.HardwareRequirement;
import com.example.starter.firmware.domain.ProfileSnapshot;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutExclusion;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceProfileRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.HardwareRequirementRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.RolloutExclusionRepository;
import com.example.starter.firmware.repo.TaskProfileSnapshotRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 投放任务：设备拉取、发布人批量筛选投放与回执。
 * 加锁顺序统一为 device 行 → release_order 行：画像更新只持 device 行锁，任务创建先锁 device
 * 再锁 release，批量投放按设备ID排序依次加锁，避免画像更新与任务创建、批量投放之间死锁，
 * 并保证每个任务只引用一个完整画像版本。
 * 版本声明硬件要求时启用画像门禁：型号、内存、标签全部满足才创建任务并冻结画像快照；
 * 不兼容设备写排除记录（含原因与画像版本），不创建任务、不计入失败率。
 * 回执首次终结任务时在发布单行锁内累计当前监控轮次统计，达到阈值即在同一事务原子暂停。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceProfileRepository profileRepository;
    private final HardwareRequirementRepository requirementRepository;
    private final RolloutExclusionRepository exclusionRepository;
    private final TaskProfileSnapshotRepository snapshotRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                       DeviceRepository deviceRepository, DeviceProfileRepository profileRepository,
                       HardwareRequirementRepository requirementRepository,
                       RolloutExclusionRepository exclusionRepository,
                       TaskProfileSnapshotRepository snapshotRepository,
                       PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, ObjectMapper objectMapper, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.profileRepository = profileRepository;
        this.requirementRepository = requirementRepository;
        this.exclusionRepository = exclusionRepository;
        this.snapshotRepository = snapshotRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则在业务条件（ACTIVE、来源版本、分桶比例）满足时，
     * 按设备当次冻结画像做兼容门禁，通过才创建任务并冻结画像快照，不兼容记排除原因且不建任务。
     * PAUSED 时不创建新任务，已有任务仍可查看与回执。
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
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return new PullResponse(TaskView.of(existing.get(), order));
            }
            if (!businessConditionsMatch(order, device)) {
                return new PullResponse(null);
            }
            GateResult gate = evaluateGate(order, deviceId);
            if (!gate.passed()) {
                exclusionRepository.upsert(order.id(), deviceId, gate.reason(), gate.profileVersion());
                return new PullResponse(null);
            }
            RolloutTask task = createTaskWithSnapshot(order, deviceId, gate.profile());
            return new PullResponse(TaskView.of(task, order));
        }, PullResponse.class);
    }

    /**
     * 发布人批量投放：按候选设备当次冻结画像筛选，兼容设备创建任务（含画像快照），
     * 不兼容设备返回原因且不计失败率。候选集合按设备ID排序加锁；同 requestId 重放首次清单，
     * 换发布单版本或候选集合返回 409。
     */
    public DispatchResponse dispatch(long releaseId, DispatchReleaseRequest request) {
        List<String> deviceIds = request.deviceIds().stream().distinct().sorted().toList();
        String fingerprint = String.join("|", "release.dispatch", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), String.join(",", deviceIds));
        return idempotency.execute(request.requestId(), "release.dispatch", fingerprint, () -> {
            // 先按固定顺序锁全部候选设备行，再锁发布单行（device → release 全局加锁顺序）
            Map<String, Device> devices = new LinkedHashMap<>();
            for (String deviceId : deviceIds) {
                Device device = deviceRepository.findByIdForUpdate(deviceId)
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
                devices.put(deviceId, device);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            List<TaskView> created = new ArrayList<>();
            List<ExclusionView> excluded = new ArrayList<>();
            for (String deviceId : deviceIds) {
                Device device = devices.get(deviceId);
                var existing = taskRepository.findByReleaseAndDevice(releaseId, deviceId);
                if (existing.isPresent()) {
                    exclusionRepository.delete(releaseId, deviceId);
                    created.add(TaskView.of(existing.get(), order));
                    continue;
                }
                if (!businessConditionsMatch(order, device)) {
                    continue;
                }
                GateResult gate = evaluateGate(order, deviceId);
                if (!gate.passed()) {
                    exclusionRepository.upsert(releaseId, deviceId, gate.reason(), gate.profileVersion());
                    excluded.add(new ExclusionView(deviceId, gate.reason().name(), gate.profileVersion()));
                    continue;
                }
                RolloutTask task = createTaskWithSnapshot(order, deviceId, gate.profile());
                created.add(TaskView.of(task, order));
            }
            return new DispatchResponse(releaseId, List.copyOf(created), List.copyOf(excluded));
        }, DispatchResponse.class);
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
     * 设备端只看自己的任务（跨发布单），不含其他设备信息。
     */
    public TaskListResponse listByDevice(String deviceId) {
        deviceService.findDevice(deviceId);
        List<TaskView> tasks = taskRepository.findByDevice(deviceId).stream()
                .map(task -> TaskView.of(task, releaseService.findOrder(task.releaseId())))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 查询发布单硬件不兼容排除清单（发布人视角，含排除原因与判定时画像版本）。
     */
    public List<RolloutExclusion> listExclusions(long releaseId) {
        releaseService.findOrder(releaseId);
        return exclusionRepository.findByRelease(releaseId);
    }

    /**
     * 发布人审计：查询某发布单下某任务创建时冻结的完整画像版本内容。
     */
    public com.example.starter.firmware.api.TaskProfileSnapshotView getTaskSnapshotView(long releaseId,
                                                                                        long taskId) {
        releaseService.findOrder(releaseId);
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        if (task.releaseId() != releaseId) {
            throw ApiException.notFound("TASK_NOT_FOUND", "任务不属于该发布单: " + taskId);
        }
        TaskProfileSnapshotRepository.SnapshotRow row = snapshotRepository.findByTaskId(taskId)
                .orElseThrow(() -> ApiException.notFound("PROFILE_SNAPSHOT_NOT_FOUND",
                        "任务未启用画像门禁，无冻结画像快照: " + taskId));
        List<String> tags;
        try {
            tags = objectMapper.readValue(row.capabilityTags(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("画像快照标签反序列化失败", e);
        }
        return com.example.starter.firmware.api.TaskProfileSnapshotView.of(row, tags);
    }

    /**
     * 业务条件：发布单 ACTIVE、设备来源版本匹配、分桶号小于投放比例。
     */
    private boolean businessConditionsMatch(ReleaseOrder order, Device device) {
        return order.status() == ReleaseStatus.ACTIVE
                && device.currentVersion().equals(order.fromVersion())
                && device.bucketNo() < order.ratio();
    }

    /**
     * 画像门禁：版本未声明硬件要求时不启用门禁（全部通过，不冻结画像版本）；
     * 声明要求时按当次冻结画像评估，设备缺少画像或画像字段缺失均给出不兼容原因。
     * 调用方已持有 device 行锁，读到的画像版本在本事务内不会被并发更新改写。
     */
    private GateResult evaluateGate(ReleaseOrder order, String deviceId) {
        Optional<HardwareRequirement> requirement = requirementRepository.findByVersion(order.toVersion());
        if (requirement.isEmpty()) {
            return GateResult.disabled();
        }
        ProfileSnapshot profile = profileRepository.findCurrent(deviceId).orElse(null);
        GateDecision decision = HardwareGate.evaluate(requirement.get(), profile);
        if (decision.compatible()) {
            return GateResult.pass(decision.profile());
        }
        Integer profileVersion = decision.profile() == null ? null : decision.profile().version();
        return GateResult.reject(decision.reason(), profileVersion);
    }

    /**
     * 创建任务并在同一事务写入画像冻结快照，同时移除该设备在本发布单的历史排除记录。
     * 唯一约束冲突（并发下另一线程已建）时回读已有任务，调用方已持锁通常不会走到。
     */
    private RolloutTask createTaskWithSnapshot(ReleaseOrder order, String deviceId, ProfileSnapshot profile) {
        Integer profileVersion = profile == null ? null : profile.version();
        long taskId;
        try {
            taskId = taskRepository.insert(order.id(), deviceId, profileVersion);
        } catch (DuplicateKeyException e) {
            return taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                    .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
        }
        if (profile != null) {
            taskRepository.insertProfileSnapshot(taskId, profile.version(), profile.model(),
                    profile.memoryMb(), writeTagsJson(profile));
        }
        exclusionRepository.delete(order.id(), deviceId);
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
    }

    private String writeTagsJson(ProfileSnapshot profile) {
        try {
            return objectMapper.writeValueAsString(profile.tags());
        } catch (Exception e) {
            throw new IllegalStateException("画像标签序列化失败", e);
        }
    }

    /**
     * 门禁内部结果：门禁未启用（版本未声明要求）时 reason/profile 均为 null，沿用旧规则不冻结画像。
     */
    private record GateResult(boolean passed,
                              com.example.starter.firmware.domain.ExclusionReason reason,
                              Integer profileVersion, ProfileSnapshot profile) {

        static GateResult disabled() {
            return new GateResult(true, null, null, null);
        }

        static GateResult pass(ProfileSnapshot profile) {
            return new GateResult(true, null, profile.version(), profile);
        }

        static GateResult reject(com.example.starter.firmware.domain.ExclusionReason reason,
                                 Integer profileVersion) {
            return new GateResult(false, reason, profileVersion, null);
        }
    }
}
