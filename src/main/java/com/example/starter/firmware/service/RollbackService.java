package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateRollbackPlanRequest;
import com.example.starter.firmware.api.ResumeRollbackPlanRequest;
import com.example.starter.firmware.api.RollbackDispatchRequest;
import com.example.starter.firmware.api.RollbackDispatchResponse;
import com.example.starter.firmware.api.RollbackHopTaskView;
import com.example.starter.firmware.api.RollbackPlanDetailResponse;
import com.example.starter.firmware.api.RollbackPlanView;
import com.example.starter.firmware.api.RollbackPauseRecordView;
import com.example.starter.firmware.api.RollbackReceiptRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RollbackHop;
import com.example.starter.firmware.domain.RollbackHopTask;
import com.example.starter.firmware.domain.RollbackPlan;
import com.example.starter.firmware.domain.RollbackPlanDevice;
import com.example.starter.firmware.domain.RollbackPlanStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.RollbackHopRepository;
import com.example.starter.firmware.repo.RollbackHopTaskRepository;
import com.example.starter.firmware.repo.RollbackPauseRecordRepository;
import com.example.starter.firmware.repo.RollbackPlanDeviceRepository;
import com.example.starter.firmware.repo.RollbackPlanRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 多跳版本回退：针对一次已结束投放，按设备成功投放历史构造连续反向路径，
 * 按 hopIndex 逐跳派发；每跳失败率达阈值自动暂停，人工恢复开启新轮次并只重派该跳未成功设备。
 * 创建/派发/回执/恢复/取消与投放拉取共用设备行锁与计划行锁，形成一致提交顺序；
 * 设备在非终态计划期间由 active_device_id 唯一约束占用，不得进入冲突任务。
 */
@Service
public class RollbackService {

    /**
     * 反向路径单跳：期望起始版本、目标版本与来源投放记录。
     */
    private record HopSpec(String expectedVersion, String targetVersion, long sourceReleaseId) {
    }

    private static final int MAX_HOPS = 5;

    private final RollbackPlanRepository planRepository;
    private final RollbackPlanDeviceRepository planDeviceRepository;
    private final RollbackHopRepository hopRepository;
    private final RollbackHopTaskRepository hopTaskRepository;
    private final RollbackPauseRecordRepository pauseRecordRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public RollbackService(RollbackPlanRepository planRepository,
                           RollbackPlanDeviceRepository planDeviceRepository,
                           RollbackHopRepository hopRepository,
                           RollbackHopTaskRepository hopTaskRepository,
                           RollbackPauseRecordRepository pauseRecordRepository,
                           ReleaseRepository releaseRepository, DeviceRepository deviceRepository,
                           TaskRepository taskRepository, IdempotencyService idempotency, Clock clock) {
        this.planRepository = planRepository;
        this.planDeviceRepository = planDeviceRepository;
        this.hopRepository = hopRepository;
        this.hopTaskRepository = hopTaskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 创建回退计划：来源投放必须已结束；逐设备校验型号、占用与冲突任务，
     * 并由成功投放历史构造连续反向路径；任一设备校验失败则整单 409，不产生任何数据。
     * 设备集合按集合语义参与指纹：同 requestId 换序重放返回原结果，异参 409。
     */
    public RollbackPlanView create(CreateRollbackPlanRequest request) {
        List<String> deviceIds = request.deviceIds().stream().distinct().sorted().toList();
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        String fingerprint = String.join("|", "rollback.create", request.planKey(),
                String.valueOf(request.sourceReleaseId()), request.targetVersion(),
                String.join(",", deviceIds), String.valueOf(sampleFloor), String.valueOf(threshold));
        return idempotency.execute(request.requestId(), "rollback.create", fingerprint, () -> {
            ReleaseOrder source = releaseRepository.findById(request.sourceReleaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND",
                            "来源投放发布单不存在: " + request.sourceReleaseId()));
            if (source.status() != ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("SOURCE_RELEASE_NOT_ENDED",
                        "来源投放尚未结束，状态: " + source.status());
            }
            if (planRepository.findByPlanKey(request.planKey()).isPresent()) {
                throw ApiException.conflict("PLAN_KEY_EXISTS", "planKey 已存在: " + request.planKey());
            }
            Map<String, List<HopSpec>> paths = new LinkedHashMap<>();
            for (String deviceId : deviceIds) {
                Device device = deviceRepository.findByIdForUpdate(deviceId)
                        .orElseThrow(() -> ApiException.conflict("DEVICE_NO_PATH",
                                "设备未登记，无投放历史: " + deviceId));
                if (!device.model().equals(source.model())) {
                    throw ApiException.conflict("DEVICE_MODEL_MISMATCH",
                            "设备型号与来源投放不一致: " + deviceId);
                }
                if (planDeviceRepository.isDeviceOccupied(deviceId)) {
                    throw ApiException.conflict("DEVICE_BUSY",
                            "设备已参与未终结回退计划: " + deviceId);
                }
                if (taskRepository.hasPendingByDevice(deviceId)) {
                    throw ApiException.conflict("DEVICE_BUSY",
                            "设备存在未终结投放任务: " + deviceId);
                }
                paths.put(deviceId, buildPath(device, request.targetVersion()));
            }
            long planId;
            try {
                planId = planRepository.insert(request.planKey(), source.id(), source.model(),
                        request.targetVersion(), sampleFloor, threshold);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("PLAN_KEY_EXISTS", "planKey 已存在: " + request.planKey());
            }
            for (Map.Entry<String, List<HopSpec>> entry : paths.entrySet()) {
                List<HopSpec> hops = entry.getValue();
                try {
                    planDeviceRepository.insert(planId, entry.getKey(), hops.size());
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("DEVICE_BUSY",
                            "设备已参与未终结回退计划: " + entry.getKey());
                }
                for (int i = 0; i < hops.size(); i++) {
                    HopSpec hop = hops.get(i);
                    hopRepository.insert(planId, entry.getKey(), i + 1, hop.expectedVersion(),
                            hop.targetVersion(), hop.sourceReleaseId());
                }
            }
            return RollbackPlanView.of(findPlan(planId));
        }, RollbackPlanView.class);
    }

    /**
     * 由设备成功投放历史构造从当前版本到目标版本的连续反向路径。
     * 历史代次不一致（相邻边断裂）、当前版本与历史末端不一致、缺路径、路径超过 5 跳均 409。
     */
    private List<HopSpec> buildPath(Device device, String targetVersion) {
        List<TaskRepository.VersionEdge> edges = taskRepository.findSuccessEdgesByDevice(device.deviceId());
        for (int i = 1; i < edges.size(); i++) {
            if (!edges.get(i).fromVersion().equals(edges.get(i - 1).toVersion())) {
                throw ApiException.conflict("DEVICE_HISTORY_INCONSISTENT",
                        "设备投放历史代次不一致: " + device.deviceId());
            }
        }
        String current = device.currentVersion();
        if (!edges.isEmpty() && !edges.get(edges.size() - 1).toVersion().equals(current)) {
            throw ApiException.conflict("DEVICE_VERSION_MISMATCH",
                    "设备当前版本与投放历史不一致: " + device.deviceId());
        }
        List<HopSpec> hops = new ArrayList<>();
        String cursor = current;
        int i = edges.size() - 1;
        while (!cursor.equals(targetVersion)) {
            if (i < 0) {
                throw ApiException.conflict("DEVICE_NO_PATH",
                        "设备无到达目标版本的反向路径: " + device.deviceId());
            }
            if (hops.size() >= MAX_HOPS) {
                throw ApiException.conflict("DEVICE_PATH_TOO_LONG",
                        "设备反向路径超过 " + MAX_HOPS + " 跳: " + device.deviceId());
            }
            TaskRepository.VersionEdge edge = edges.get(i);
            hops.add(new HopSpec(edge.toVersion(), edge.fromVersion(), edge.releaseId()));
            cursor = edge.fromVersion();
            i--;
        }
        if (hops.isEmpty()) {
            throw ApiException.conflict("DEVICE_NO_PATH",
                    "目标版本即设备当前版本，无有效回退路径: " + device.deviceId());
        }
        return hops;
    }

    /**
     * 设备派发：仅 ACTIVE 可派发新跳；同一设备仅在上一跳 SUCCESS 后进入下一跳。
     * 本轮已派发的跳幂等返回原任务；上轮未成功的跳在恢复后的新轮次重新派发。
     * 设备存在未终结投放任务时 409，不与新投放并发冲突。
     */
    public RollbackDispatchResponse dispatch(long planId, RollbackDispatchRequest request) {
        String fingerprint = String.join("|", "rollback.dispatch", String.valueOf(planId),
                request.deviceId());
        return idempotency.execute(request.requestId(), "rollback.dispatch", fingerprint, () -> {
            RollbackPlan plan = planRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "回退计划不存在: " + planId));
            if (plan.status() == RollbackPlanStatus.CANCELLED) {
                throw ApiException.conflict("PLAN_CANCELLED", "回退计划已取消，不再派发");
            }
            if (plan.status() == RollbackPlanStatus.COMPLETED) {
                throw ApiException.conflict("PLAN_COMPLETED", "回退计划已完成，不再派发");
            }
            if (plan.status() == RollbackPlanStatus.PAUSED) {
                throw ApiException.conflict("PLAN_PAUSED", "回退计划已暂停，尚未派发和后续跳停止");
            }
            RollbackPlanDevice member = planDeviceRepository.find(planId, request.deviceId())
                    .orElseThrow(() -> ApiException.conflict("DEVICE_NOT_IN_PLAN",
                            "设备不属于该回退计划: " + request.deviceId()));
            deviceRepository.findByIdForUpdate(request.deviceId())
                    .orElseThrow(() -> ApiException.conflict("DEVICE_NO_PATH",
                            "设备未登记: " + request.deviceId()));
            if (taskRepository.hasPendingByDevice(request.deviceId())) {
                throw ApiException.conflict("DEVICE_BUSY",
                        "设备存在未终结投放任务: " + request.deviceId());
            }
            for (int hopIndex = 1; hopIndex <= member.hopCount(); hopIndex++) {
                var latest = hopTaskRepository.findLatest(planId, request.deviceId(), hopIndex);
                if (latest.isPresent() && latest.get().status() == TaskStatus.SUCCESS) {
                    continue;
                }
                if (latest.isPresent() && latest.get().roundNo() == plan.monitorRound()) {
                    return new RollbackDispatchResponse(RollbackHopTaskView.of(latest.get()));
                }
                RollbackHop hopDef = hopRepository.find(planId, request.deviceId(), hopIndex)
                        .orElseThrow(() -> new IllegalStateException("跳定义缺失"));
                String receiptKey = "rk" + UUID.randomUUID().toString().replace("-", "");
                try {
                    long taskId = hopTaskRepository.insert(receiptKey, planId, request.deviceId(),
                            hopIndex, plan.monitorRound(), hopDef.expectedVersion(),
                            hopDef.targetVersion(), hopDef.sourceReleaseId());
                    return new RollbackDispatchResponse(RollbackHopTaskView.of(
                            hopTaskRepository.findById(taskId)
                                    .orElseThrow(() -> new IllegalStateException("回跳任务创建后读取失败"))));
                } catch (DuplicateKeyException e) {
                    RollbackHopTask existing = hopTaskRepository
                            .findLatest(planId, request.deviceId(), hopIndex)
                            .orElseThrow(() -> new IllegalStateException("回跳任务唯一约束冲突后未找到任务"));
                    return new RollbackDispatchResponse(RollbackHopTaskView.of(existing));
                }
            }
            return new RollbackDispatchResponse(null);
        }, RollbackDispatchResponse.class);
    }

    /**
     * 回执：receiptKey 必须与派发凭证一致；首次回执终结任务并计入该跳本轮统计，
     * SUCCESS 原子切换设备版本到本跳目标版本，FAILED 保留版本；
     * 该跳本轮样本达下限且失败率越限时同事务原子暂停计划；
     * 全部设备到达目标后计划完结并释放设备占用；取消后不再接收新回执。
     */
    public RollbackHopTaskView receipt(long hopTaskId, RollbackReceiptRequest request) {
        String fingerprint = String.join("|", "rollback.receipt", String.valueOf(hopTaskId),
                request.receiptKey(), request.result().name());
        return idempotency.execute(request.requestId(), "rollback.receipt", fingerprint, () -> {
            RollbackHopTask snapshot = hopTaskRepository.findById(hopTaskId)
                    .orElseThrow(() -> ApiException.notFound("HOP_TASK_NOT_FOUND",
                            "回跳任务不存在: " + hopTaskId));
            if (!snapshot.receiptKey().equals(request.receiptKey())) {
                throw ApiException.conflict("RECEIPT_KEY_MISMATCH", "receiptKey 与任务凭证不匹配");
            }
            RollbackPlan lockedPlan = planRepository.findByIdForUpdate(snapshot.planId())
                    .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "回退计划不存在"));
            RollbackHopTask task = hopTaskRepository.findByIdForUpdate(hopTaskId)
                    .orElseThrow(() -> ApiException.notFound("HOP_TASK_NOT_FOUND",
                            "回跳任务不存在: " + hopTaskId));
            return switch (task.status()) {
                case PENDING -> {
                    if (lockedPlan.status() == RollbackPlanStatus.CANCELLED) {
                        throw ApiException.conflict("PLAN_CANCELLED", "回退计划已取消，不再接收新回执");
                    }
                    hopTaskRepository.complete(hopTaskId, request.result());
                    planRepository.incrementRoundStats(lockedPlan.id(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(task.deviceId(), task.targetVersion());
                    }
                    RollbackPlan updated = planRepository.findById(lockedPlan.id())
                            .orElseThrow(() -> new IllegalStateException("计划行锁内读取失败"));
                    pauseIfThresholdReached(updated, task);
                    completeIfAllArrived(updated.id());
                    yield RollbackHopTaskView.of(hopTaskRepository.findById(hopTaskId)
                            .orElseThrow(() -> new IllegalStateException("回执后读取任务失败")));
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield RollbackHopTaskView.of(task);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, RollbackHopTaskView.class);
    }

    /**
     * 该跳本轮样本达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的计划原子转为 PAUSED。
     * 调用方持有计划行锁，并发回执串行通过，每轮至多生成一条暂停记录。
     */
    private void pauseIfThresholdReached(RollbackPlan plan, RollbackHopTask trigger) {
        if (plan.status() != RollbackPlanStatus.ACTIVE) {
            return;
        }
        int success = hopTaskRepository.countByHopRoundAndResult(plan.id(), trigger.hopIndex(),
                plan.monitorRound(), ReceiptResult.SUCCESS);
        int failed = hopTaskRepository.countByHopRoundAndResult(plan.id(), trigger.hopIndex(),
                plan.monitorRound(), ReceiptResult.FAILED);
        int samples = success + failed;
        if (samples < plan.sampleFloor()) {
            return;
        }
        if ((long) failed * 100 < (long) samples * plan.failureThresholdPercent()) {
            return;
        }
        if (planRepository.pauseIfActive(plan.id(), trigger.hopIndex()) == 1) {
            pauseRecordRepository.insert(plan.id(), plan.monitorRound(), trigger.hopIndex(),
                    trigger.id(), success, failed, Instant.now(clock).toString());
        }
    }

    /**
     * 全部设备到达目标版本后完结计划并释放设备占用；仅当计划仍未终结时生效。
     */
    private void completeIfAllArrived(long planId) {
        if (planDeviceRepository.countDevicesNotCompleted(planId) > 0) {
            return;
        }
        if (planRepository.completeIfOpen(planId) == 1) {
            planDeviceRepository.releaseByPlan(planId);
        }
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复为 ACTIVE；开启新监控轮次并清零统计，
     * 取消暂停跳未回执的旧轮次任务，新轮次只包含该跳未成功设备（逐设备派发时惰性生成）。
     */
    public RollbackPlanView resume(long planId, ResumeRollbackPlanRequest request) {
        String fingerprint = String.join("|", "rollback.resume", String.valueOf(planId),
                request.reason());
        return idempotency.execute(request.requestId(), "rollback.resume", fingerprint, () -> {
            RollbackPlan plan = planRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "回退计划不存在: " + planId));
            if (plan.status() == RollbackPlanStatus.CANCELLED) {
                throw ApiException.conflict("PLAN_CANCELLED", "回退计划已取消，不能恢复");
            }
            if (plan.status() != RollbackPlanStatus.PAUSED) {
                throw ApiException.conflict("PLAN_NOT_PAUSED",
                        "回退计划状态为 " + plan.status() + "，仅 PAUSED 可恢复");
            }
            planRepository.resume(planId);
            hopTaskRepository.cancelPendingByHop(planId, plan.pausedHopIndex());
            return RollbackPlanView.of(findPlan(planId));
        }, RollbackPlanView.class);
    }

    /**
     * 取消：ACTIVE/PAUSED 转 CANCELLED，未终结回跳任务转 CANCELLED 并释放设备占用；
     * 终态下重复取消幂等返回当前状态。
     */
    public RollbackPlanView cancel(long planId, String requestId) {
        String fingerprint = String.join("|", "rollback.cancel", String.valueOf(planId));
        return idempotency.execute(requestId, "rollback.cancel", fingerprint, () -> {
            RollbackPlan plan = planRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "回退计划不存在: " + planId));
            if (plan.status() == RollbackPlanStatus.ACTIVE || plan.status() == RollbackPlanStatus.PAUSED) {
                planRepository.cancelIfOpen(planId);
                hopTaskRepository.cancelPendingByPlan(planId);
                planDeviceRepository.releaseByPlan(planId);
            }
            return RollbackPlanView.of(findPlan(planId));
        }, RollbackPlanView.class);
    }

    /**
     * 计划明细（只读）：计划概要、逐设备逐跳历史、轮次统计与暂停记录，不触发状态变化。
     */
    public RollbackPlanDetailResponse detail(long planId) {
        RollbackPlan plan = findPlan(planId);
        List<RollbackPlanDetailResponse.RollbackDeviceHistory> devices = new ArrayList<>();
        for (RollbackPlanDevice member : planDeviceRepository.findByPlan(planId)) {
            List<RollbackHop> hopDefs = hopRepository.findByPlanAndDevice(planId, member.deviceId());
            Map<Integer, List<RollbackHopTask>> tasksByHop = hopTaskRepository
                    .findByPlanAndDevice(planId, member.deviceId()).stream()
                    .collect(Collectors.groupingBy(RollbackHopTask::hopIndex));
            List<RollbackPlanDetailResponse.RollbackHopHistory> hops = hopDefs.stream()
                    .map(hop -> new RollbackPlanDetailResponse.RollbackHopHistory(hop.hopIndex(),
                            hop.expectedVersion(), hop.targetVersion(), hop.sourceReleaseId(),
                            tasksByHop.getOrDefault(hop.hopIndex(), List.of()).stream()
                                    .map(RollbackHopTaskView::of).toList()))
                    .toList();
            devices.add(new RollbackPlanDetailResponse.RollbackDeviceHistory(member.deviceId(),
                    member.hopCount(), hops));
        }
        List<RollbackPlanDetailResponse.RollbackRoundStats> rounds = hopTaskRepository.roundStats(planId)
                .stream()
                .map(row -> new RollbackPlanDetailResponse.RollbackRoundStats(
                        (int) row[0], (int) row[1], (int) row[2]))
                .toList();
        List<RollbackPauseRecordView> pauses = pauseRecordRepository.findByPlan(planId).stream()
                .map(RollbackPauseRecordView::of).toList();
        return new RollbackPlanDetailResponse(RollbackPlanView.of(plan), devices, rounds, pauses);
    }

    public RollbackPlan findPlan(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("PLAN_NOT_FOUND", "回退计划不存在: " + planId));
    }
}
