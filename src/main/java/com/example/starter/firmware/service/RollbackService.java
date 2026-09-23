package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateRollbackPlanRequest;
import com.example.starter.firmware.api.DispatchRollbackRequest;
import com.example.starter.firmware.api.ReceiptRollbackRequest;
import com.example.starter.firmware.api.ResumeRollbackPlanRequest;
import com.example.starter.firmware.api.RollbackDispatchResponse;
import com.example.starter.firmware.api.RollbackPlanDetailView;
import com.example.starter.firmware.api.RollbackPlanView;
import com.example.starter.firmware.api.RollbackTaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ForwardDeployment;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RollbackPlan;
import com.example.starter.firmware.domain.RollbackPlanHop;
import com.example.starter.firmware.domain.RollbackPlanStatus;
import com.example.starter.firmware.domain.RollbackTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceOccupationRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.ForwardHistoryRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.RollbackHopRepository;
import com.example.starter.firmware.repo.RollbackPlanRepository;
import com.example.starter.firmware.repo.RollbackTaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 多跳版本回退：创建时按设备成功投放历史构造并冻结连续反向路径（每设备1~5跳，可不同），
 * 按 hopIndex 波次派发；同一设备上一跳 SUCCESS 后才进入下一跳。回执在计划行锁内终结任务、
 * 原子条件切换版本、累计（跳,轮）统计并评估失败率门禁；PAUSED 后未派发与后续跳停止，
 * 人工恢复生成当前跳新 round，仅含未成功设备；全部设备到达目标转 COMPLETED；取消后不再接收回执。
 */
@Service
public class RollbackService {

    private static final int MAX_PATH_LENGTH = 5;

    private final RollbackPlanRepository planRepository;
    private final RollbackHopRepository hopRepository;
    private final RollbackTaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final ForwardHistoryRepository forwardHistoryRepository;
    private final DeviceOccupationRepository occupationRepository;
    private final IdempotencyService idempotency;

    public RollbackService(RollbackPlanRepository planRepository, RollbackHopRepository hopRepository,
                           RollbackTaskRepository taskRepository, ReleaseRepository releaseRepository,
                           DeviceRepository deviceRepository,
                           ForwardHistoryRepository forwardHistoryRepository,
                           DeviceOccupationRepository occupationRepository,
                           IdempotencyService idempotency) {
        this.planRepository = planRepository;
        this.hopRepository = hopRepository;
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.forwardHistoryRepository = forwardHistoryRepository;
        this.occupationRepository = occupationRepository;
        this.idempotency = idempotency;
    }

    /**
     * 一台设备构造出的反向路径。
     */
    private record BuiltPath(List<RollbackPlanHop> hops) {
    }

    public RollbackPlanView createPlan(CreateRollbackPlanRequest request) {
        List<String> devices = normalizeDevices(request.devices());
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        String fingerprint = String.join("|", "rollback.plan.create", request.planKey(),
                String.valueOf(request.sourceReleaseId()), request.targetVersion(),
                String.join(",", devices), String.valueOf(sampleFloor), String.valueOf(threshold));
        return idempotency.execute(request.requestId(), "rollback.plan.create", fingerprint, () -> {
            ReleaseOrder source = releaseRepository.findByIdForUpdate(request.sourceReleaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND",
                            "来源投放单不存在: " + request.sourceReleaseId()));
            if (source.status() != ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_NOT_FINISHED",
                        "仅可针对已结束（CANCELLED）投放单创建回退计划，当前状态: " + source.status());
            }

            // 先逐设备校验并构造路径，全部通过后再占用与落库（任一设备不合法整单409）。
            Map<String, BuiltPath> paths = new TreeMap<>();
            int maxHop = 0;
            for (String deviceId : devices) {
                Device device = deviceRepository.findByIdForUpdate(deviceId)
                        .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
                if (!forwardHistoryRepository.existsTaskForRelease(deviceId, request.sourceReleaseId())) {
                    throw ApiException.conflict("DEVICE_NOT_IN_SOURCE_RELEASE",
                            "设备不属于来源投放单，完整设备集合必须来自该次投放: " + deviceId);
                }
                if (forwardHistoryRepository.existsPendingForwardTask(deviceId)) {
                    throw ApiException.conflict("DEVICE_BUSY",
                            "设备存在未终结（PENDING）的正向投放任务，不能进入回退计划: " + deviceId);
                }
                BuiltPath path = buildPath(deviceId, device.currentVersion(), request.targetVersion());
                paths.put(deviceId, path);
                maxHop = Math.max(maxHop, path.hops().size() - 1);
            }

            long planId;
            try {
                planId = planRepository.insert(request.planKey(), request.sourceReleaseId(),
                        request.targetVersion(), maxHop, sampleFloor, threshold);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("PLAN_KEY_EXISTS", "planKey 已被占用: " + request.planKey());
            }

            try {
                for (String deviceId : devices) {
                    occupationRepository.tryOccupy(deviceId, DeviceOccupationRepository.SCOPE_ROLLBACK, planId);
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_BUSY", "集合中存在设备正被冲突任务占用，整单拒绝");
            }

            for (Map.Entry<String, BuiltPath> entry : paths.entrySet()) {
                for (RollbackPlanHop hop : entry.getValue().hops()) {
                    hopRepository.insert(planId, entry.getKey(), hop.hopIndex(), hop.expectedVersion(),
                            hop.toVersion(), hop.sourceReleaseId(), hop.sourceTaskId());
                }
            }
            return RollbackPlanView.of(findPlan(planId));
        }, RollbackPlanView.class);
    }

    /**
     * 设备拉取回退波次任务：仅 ACTIVE 计划、当前（跳,轮）、且该设备在本跳有路径且本跳尚未 SUCCESS 时派发；
     * 已有任务（含 FAILED/CANCELLED）直接返回，不重复派发；PAUSED/后续跳/已到目标均返回空。
     */
    public RollbackDispatchResponse dispatch(long planId, DispatchRollbackRequest request) {
        String fingerprint = String.join("|", "rollback.dispatch", String.valueOf(planId), request.deviceId());
        return idempotency.execute(request.requestId(), "rollback.dispatch", fingerprint, () -> {
            RollbackPlan plan = planRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> ApiException.notFound("ROLLBACK_PLAN_NOT_FOUND",
                            "回退计划不存在: " + planId));
            Device device = deviceRepository.findById(request.deviceId())
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + request.deviceId()));
            if (plan.status() != RollbackPlanStatus.ACTIVE) {
                return new RollbackDispatchResponse(null);
            }
            RollbackPlanHop hop = hopRepository.findByPlanAndHop(planId, plan.currentHop()).stream()
                    .filter(h -> h.deviceId().equals(request.deviceId()))
                    .findFirst().orElse(null);
            if (hop == null) {
                // 该设备路径不含当前跳：已到达其目标版本，本跳不参与。
                return new RollbackDispatchResponse(null);
            }
            if (taskRepository.existsSuccess(planId, plan.currentHop(), request.deviceId())) {
                return new RollbackDispatchResponse(null);
            }
            var existing = taskRepository.findByPlan(planId).stream()
                    .filter(t -> t.deviceId().equals(request.deviceId()) && t.hopIndex() == plan.currentHop())
                    .reduce((first, second) -> second)
                    .orElse(null);
            if (existing != null) {
                // 同跳已有任务：PENDING/FAILED/CANCELLED 均原样返回，恢复后的新轮次由 resume 预先建任务。
                return new RollbackDispatchResponse(RollbackTaskView.of(existing));
            }
            if (!device.currentVersion().equals(hop.expectedVersion())) {
                throw ApiException.conflict("DEVICE_VERSION_DRIFT",
                        "设备当前版本与该跳冻结的期望版本不一致: " + request.deviceId());
            }
            long taskId = taskRepository.insert(planId, plan.currentHop(), plan.currentRound(),
                    request.deviceId(), hop.expectedVersion(), hop.toVersion(), hop.sourceReleaseId());
            return new RollbackDispatchResponse(RollbackTaskView.of(taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("回退任务创建后读取失败"))));
        }, RollbackDispatchResponse.class);
    }

    /**
     * 回退回执：PENDING 终结并按冻结 expectedVersion 原子条件切换版本；FAILED 保留版本；
     * 统计仅计入任务（跳,轮）与计划当前（跳,轮）一致的首次回执；整跳全部设备 SUCCESS 后进入下一跳或完成；
     * 样本达标且失败率达到阈值（含等于）同事务 PAUSED。
     */
    public RollbackTaskView receipt(long taskId, ReceiptRollbackRequest request) {
        String fingerprint = String.join("|", "rollback.receipt", String.valueOf(taskId),
                request.receiptKey(), request.result().name());
        return idempotency.execute(request.requestId(), "rollback.receipt", fingerprint, () -> {
            RollbackTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("ROLLBACK_TASK_NOT_FOUND",
                            "回退任务不存在: " + taskId));
            RollbackPlan lockedPlan = planRepository.findByIdForUpdate(snapshot.planId())
                    .orElseThrow(() -> ApiException.notFound("ROLLBACK_PLAN_NOT_FOUND", "回退计划不存在"));
            RollbackTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("ROLLBACK_TASK_NOT_FOUND",
                            "回退任务不存在: " + taskId));

            switch (task.status()) {
                case PENDING -> {
                    if (lockedPlan.status() == RollbackPlanStatus.CANCELLED) {
                        throw ApiException.conflict("PLAN_CANCELLED", "回退计划已取消，回执不再受理");
                    }
                    try {
                        taskRepository.complete(taskId, request.result(), request.receiptKey());
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("RECEIPT_KEY_CONFLICT",
                                "receiptKey 已被占用: " + request.receiptKey());
                    }
                    if (request.result() == ReceiptResult.SUCCESS) {
                        int switched = deviceRepository.updateCurrentVersionIfMatch(
                                task.deviceId(), task.toVersion(), task.expectedVersion());
                        if (switched == 0) {
                            throw ApiException.conflict("DEVICE_VERSION_DRIFT",
                                    "设备当前版本与冻结期望版本不一致，未切换版本: " + task.deviceId());
                        }
                        // 设备已成功走完自身最后一跳（到达其目标版本）：释放占用，允许其进入后续冲突任务。
                        if (task.hopIndex() >= hopRepository.findMaxHopByDevice(lockedPlan.id(), task.deviceId())) {
                            occupationRepository.release(task.deviceId(),
                                    DeviceOccupationRepository.SCOPE_ROLLBACK, lockedPlan.id());
                        }
                    }
                    boolean currentWave = task.hopIndex() == lockedPlan.currentHop()
                            && task.round() == lockedPlan.currentRound();
                    if (currentWave) {
                        planRepository.incrementRoundStats(lockedPlan.id(), request.result());
                    }
                    RollbackPlan updated = planRepository.findById(lockedPlan.id()).orElseThrow();
                    if (currentWave) {
                        // 任一回执使样本达到下限即评估；先暂停后推进，FAILED>0 时不可能同时满足全成功。
                        pauseIfThresholdReached(updated);
                    }
                    maybeAdvanceOrComplete(updated);
                    return RollbackTaskView.of(taskRepository.findById(taskId).orElseThrow());
                }
                case SUCCESS, FAILED -> {
                    if (task.firstResult() != request.result()) {
                        throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                                "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                    }
                    if (task.receiptKey() != null && !task.receiptKey().equals(request.receiptKey())) {
                        throw ApiException.conflict("RECEIPT_KEY_CONFLICT",
                                "任务已由回执 " + task.receiptKey() + " 终结，receiptKey 不一致");
                    }
                    return RollbackTaskView.of(task);
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            }
            throw new IllegalStateException("未知任务状态");
        }, RollbackTaskView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复；当前跳不变、轮次加一、统计清零；作废旧轮次仍 PENDING 的任务，
     * 仅为该跳尚未 SUCCESS 的设备生成新 round 任务。
     */
    public RollbackPlanView resume(long planId, ResumeRollbackPlanRequest request) {
        String fingerprint = String.join("|", "rollback.plan.resume", String.valueOf(planId), request.reason());
        return idempotency.execute(request.requestId(), "rollback.plan.resume", fingerprint, () -> {
            RollbackPlan plan = planRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> ApiException.notFound("ROLLBACK_PLAN_NOT_FOUND",
                            "回退计划不存在: " + planId));
            if (plan.status() == RollbackPlanStatus.CANCELLED) {
                throw ApiException.conflict("PLAN_CANCELLED", "回退计划已取消，不能恢复");
            }
            if (plan.status() == RollbackPlanStatus.COMPLETED) {
                throw ApiException.conflict("PLAN_COMPLETED", "回退计划已完成，不能恢复");
            }
            if (plan.status() != RollbackPlanStatus.PAUSED) {
                throw ApiException.conflict("PLAN_NOT_PAUSED",
                        "回退计划状态为 " + plan.status() + "，仅 PAUSED 可恢复");
            }
            int newRound = plan.currentRound() + 1;
            if (planRepository.resume(planId) != 1) {
                throw ApiException.conflict("PLAN_NOT_PAUSED", "回退计划状态已变化，恢复失败");
            }
            taskRepository.cancelPendingByPlanHopRound(planId, plan.currentHop(), plan.currentRound());
            dispatchResumeWave(planId, plan.currentHop(), newRound);
            RollbackPlan updated = planRepository.findById(planId).orElseThrow();
            // 边界：暂停期间设备已全部成功（理论上失败任务不可改结果，不会发生），兜底推进。
            if (countHopSuccessDevices(updated) == countHopDevices(updated, updated.currentHop())) {
                maybeAdvanceOrComplete(updated);
            }
            return RollbackPlanView.of(planRepository.findById(planId).orElseThrow());
        }, RollbackPlanView.class);
    }

    public RollbackPlanView cancel(long planId, String requestId) {
        String fingerprint = String.join("|", "rollback.plan.cancel", String.valueOf(planId));
        return idempotency.execute(requestId, "rollback.plan.cancel", fingerprint, () -> {
            RollbackPlan plan = planRepository.findByIdForUpdate(planId)
                    .orElseThrow(() -> ApiException.notFound("ROLLBACK_PLAN_NOT_FOUND",
                            "回退计划不存在: " + planId));
            if (plan.status() == RollbackPlanStatus.ACTIVE || plan.status() == RollbackPlanStatus.PAUSED) {
                planRepository.cancel(planId);
                taskRepository.cancelPendingByPlan(planId);
                occupationRepository.releaseAll(DeviceOccupationRepository.SCOPE_ROLLBACK, planId);
            }
            return RollbackPlanView.of(findPlan(planId));
        }, RollbackPlanView.class);
    }

    /**
     * 查询计划详情：设备逐跳冻结路径与各（跳,轮）统计，只读，不触发任何状态变化。
     */
    public RollbackPlanDetailView detail(long planId) {
        RollbackPlan plan = findPlan(planId);
        List<RollbackPlanHop> hops = hopRepository.findByPlan(planId);
        List<RollbackTask> tasks = taskRepository.findByPlan(planId);

        Map<String, List<RollbackTask>> tasksByDeviceHop = new TreeMap<>();
        Map<String, int[]> roundCounters = new TreeMap<>();
        for (RollbackTask task : tasks) {
            tasksByDeviceHop.computeIfAbsent(task.deviceId() + "#" + task.hopIndex(), k -> new ArrayList<>()).add(task);
            String roundKey = task.hopIndex() + "#" + task.round();
            int[] counters = roundCounters.computeIfAbsent(roundKey, k -> new int[4]);
            counters[0]++;
            switch (task.status()) {
                case SUCCESS -> counters[1]++;
                case FAILED -> counters[2]++;
                case PENDING, CANCELLED -> {
                    if (task.status() == TaskStatus.PENDING) {
                        counters[3]++;
                    }
                }
            }
        }

        List<RollbackPlanDetailView.DevicePathView> devicePaths = hops.stream().map(hop -> {
            List<RollbackTask> hopTasks = tasksByDeviceHop
                    .getOrDefault(hop.deviceId() + "#" + hop.hopIndex(), List.of());
            RollbackTask success = hopTasks.stream().filter(t -> t.status() == TaskStatus.SUCCESS)
                    .findFirst().orElse(null);
            String hopStatus;
            Integer completedRound;
            if (success != null) {
                hopStatus = "SUCCESS";
                completedRound = success.round();
            } else {
                RollbackTask last = hopTasks.isEmpty() ? null : hopTasks.get(hopTasks.size() - 1);
                if (last == null) {
                    hopStatus = "WAITING";
                    completedRound = null;
                } else {
                    hopStatus = last.status().name();
                    completedRound = last.status() == TaskStatus.PENDING ? null : last.round();
                }
            }
            return new RollbackPlanDetailView.DevicePathView(hop.deviceId(), hop.hopIndex(),
                    hop.expectedVersion(), hop.toVersion(), hop.sourceReleaseId(), hop.sourceTaskId(),
                    hopStatus, completedRound);
        }).toList();

        List<RollbackPlanDetailView.RoundStatView> rounds = roundCounters.entrySet().stream()
                .map(entry -> {
                    String[] parts = entry.getKey().split("#");
                    int[] c = entry.getValue();
                    return new RollbackPlanDetailView.RoundStatView(Integer.parseInt(parts[0]),
                            Integer.parseInt(parts[1]), c[0], c[1], c[2], c[3]);
                })
                .sorted((a, b) -> a.hopIndex() == b.hopIndex() ? Integer.compare(a.round(), b.round())
                        : Integer.compare(a.hopIndex(), b.hopIndex()))
                .toList();

        return new RollbackPlanDetailView(RollbackPlanView.of(plan), devicePaths, rounds);
    }

    public RollbackPlan findPlan(long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> ApiException.notFound("ROLLBACK_PLAN_NOT_FOUND", "回退计划不存在: " + planId));
    }

    /**
     * 依据设备成功投放历史构造从当前版本到目标版本的连续反向路径。
     * 历史必须逐代首尾相接且最后一代目标等于设备当前版本；目标版本必须是链上某个来源版本；
     * 路径长度要求1~5，否则以 409 拒绝（缺路径/当前版本不一致/历史代次不一致/路径超长）。
     */
    private BuiltPath buildPath(String deviceId, String currentVersion, String targetVersion) {
        List<ForwardDeployment> history = forwardHistoryRepository.findSuccessHistory(deviceId);
        if (history.isEmpty()) {
            throw ApiException.conflict("ROLLBACK_PATH_NOT_FOUND",
                    "设备缺少已完成投放历史，无法构造回退路径: " + deviceId);
        }
        for (int i = 0; i < history.size(); i++) {
            ForwardDeployment step = history.get(i);
            if (i > 0 && !step.fromVersion().equals(history.get(i - 1).toVersion())) {
                throw ApiException.conflict("ROLLBACK_HISTORY_MISMATCH",
                        "设备投放历史代次不连续，无法构造回退路径: " + deviceId);
            }
        }
        ForwardDeployment last = history.get(history.size() - 1);
        if (!last.toVersion().equals(currentVersion)) {
            throw ApiException.conflict("ROLLBACK_CURRENT_VERSION_MISMATCH",
                    "设备当前版本与投放历史末代版本不一致: " + deviceId);
        }
        int targetIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).fromVersion().equals(targetVersion)) {
                targetIndex = i;
                break;
            }
        }
        if (targetIndex < 0) {
            throw ApiException.conflict("ROLLBACK_PATH_NOT_FOUND",
                    "目标版本不在设备投放历史链上，缺少回退路径: " + deviceId);
        }
        int length = history.size() - targetIndex;
        if (length < 1 || length > MAX_PATH_LENGTH) {
            throw ApiException.conflict("ROLLBACK_PATH_LENGTH_INVALID",
                    "设备回退路径长度必须在1~5之间，实际: " + length + "，设备: " + deviceId);
        }
        List<RollbackPlanHop> hops = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            ForwardDeployment step = history.get(history.size() - 1 - i);
            String expected = i == 0 ? currentVersion : history.get(history.size() - i).fromVersion();
            hops.add(new RollbackPlanHop(0, 0, deviceId, i, expected, step.fromVersion(),
                    step.releaseId(), step.taskId()));
        }
        if (!hops.get(hops.size() - 1).toVersion().equals(targetVersion)) {
            throw ApiException.conflict("ROLLBACK_PATH_NOT_FOUND",
                    "回退路径终点与目标版本不一致: " + deviceId);
        }
        return new BuiltPath(hops);
    }

    /**
     * 当前跳样本达标且 FAILED×100 >= 样本数×阈值 时原子 PAUSED；调用方持有计划行锁。
     */
    private void pauseIfThresholdReached(RollbackPlan plan) {
        if (plan.status() != RollbackPlanStatus.ACTIVE) {
            return;
        }
        int samples = plan.roundSuccess() + plan.roundFailed();
        if (samples < plan.sampleFloor()) {
            return;
        }
        if ((long) plan.roundFailed() * 100 < (long) samples * plan.failureThresholdPercent()) {
            return;
        }
        planRepository.pauseIfActive(plan.id());
    }

    /**
     * 若当前跳所有参与设备均已 SUCCESS：最后一跳转 COMPLETED 并释放占用，否则进入下一跳。
     * 调用方持有计划行锁。
     */
    private void maybeAdvanceOrComplete(RollbackPlan plan) {
        RollbackPlan fresh = planRepository.findById(plan.id()).orElseThrow();
        if (fresh.status() != RollbackPlanStatus.ACTIVE) {
            return;
        }
        int hopDevices = countHopDevices(fresh, fresh.currentHop());
        if (hopDevices == 0 || countHopSuccessDevices(fresh) != hopDevices) {
            return;
        }
        if (fresh.currentHop() >= fresh.maxHop()) {
            if (planRepository.complete(fresh.id()) == 1) {
                occupationRepository.releaseAll(DeviceOccupationRepository.SCOPE_ROLLBACK, fresh.id());
            }
        } else {
            planRepository.advanceHop(fresh.id(), fresh.currentHop());
        }
    }

    private int countHopDevices(RollbackPlan plan, int hopIndex) {
        return hopRepository.findByPlanAndHop(plan.id(), hopIndex).size();
    }

    private int countHopSuccessDevices(RollbackPlan plan) {
        int success = 0;
        for (RollbackPlanHop hop : hopRepository.findByPlanAndHop(plan.id(), plan.currentHop())) {
            if (taskRepository.existsSuccess(plan.id(), plan.currentHop(), hop.deviceId())) {
                success++;
            }
        }
        return success;
    }

    /**
     * 恢复时为当前跳未成功设备派发新 round 任务，冻结值取自路径行。
     */
    private void dispatchResumeWave(long planId, int hopIndex, int newRound) {
        for (RollbackPlanHop hop : hopRepository.findByPlanAndHop(planId, hopIndex)) {
            if (taskRepository.existsSuccess(planId, hopIndex, hop.deviceId())) {
                continue;
            }
            Device device = deviceRepository.findById(hop.deviceId()).orElseThrow();
            if (!device.currentVersion().equals(hop.expectedVersion())) {
                throw ApiException.conflict("DEVICE_VERSION_DRIFT",
                        "设备当前版本与冻结期望版本不一致，不能恢复派发: " + hop.deviceId());
            }
            taskRepository.insert(planId, hopIndex, newRound, hop.deviceId(), hop.expectedVersion(),
                    hop.toVersion(), hop.sourceReleaseId());
        }
    }

    private List<String> normalizeDevices(List<String> input) {
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("DEVICES_EMPTY", "设备集合不能为空");
        }
        List<String> sorted = input.stream().sorted().distinct().toList();
        if (sorted.size() != input.size()) {
            throw ApiException.badRequest("DEVICES_DUPLICATED", "设备集合中存在重复设备");
        }
        if (sorted.stream().anyMatch(String::isBlank)) {
            throw ApiException.badRequest("DEVICE_ID_BLANK", "设备ID不能为空");
        }
        return sorted;
    }
}
