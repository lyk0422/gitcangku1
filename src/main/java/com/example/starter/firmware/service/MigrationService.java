package com.example.starter.firmware.service;

import com.example.starter.firmware.api.MigrationActivateResponse;
import com.example.starter.firmware.api.MigrationDetailResponse;
import com.example.starter.firmware.api.MigrationItemInput;
import com.example.starter.firmware.api.MigrationPreviewResponse;
import com.example.starter.firmware.domain.AssignmentCommand;
import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortAssignment;
import com.example.starter.firmware.domain.CohortRegion;
import com.example.starter.firmware.domain.MigrationItem;
import com.example.starter.firmware.domain.MigrationOrder;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.repo.MigrationRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨投放队列迁移单：预览（完整后态、不写数据）、激活（单事务整单原子提交）与只读查询。
 *
 * <p>激活在一个事务内重新读取设备分配、活动状态、队列策略与未决下发指令：
 * 任一设备版本变化、活动结束、目标不适用或完整后态越界即整单 409/422，不迁移部分设备、不提前废弃指令。
 * 设备有未决指令时原子将旧指令置 SUPERSEDED 并为目标队列生成新 assignmentGeneration；无未决指令也递增代次。
 *
 * <p>并发顺序：激活先锁活动行，再锁该活动全部队列/区域行与各设备分配/未决指令行，
 * 因此与回执入账、自动暂停、人工恢复及另一迁移按提交顺序串行，统计、设备归属与活动状态来自同一结果。
 */
@Service
public class MigrationService {

    private final MigrationRepository migrationRepository;
    private final CohortRepository cohortRepository;
    private final AssignmentRepository assignmentRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public MigrationService(MigrationRepository migrationRepository, CohortRepository cohortRepository,
                            AssignmentRepository assignmentRepository, ReleaseRepository releaseRepository,
                            IdempotencyService idempotency, Clock clock) {
        this.migrationRepository = migrationRepository;
        this.cohortRepository = cohortRepository;
        this.assignmentRepository = assignmentRepository;
        this.releaseRepository = releaseRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 预览：按完整后态计算受影响队列规模、灰度上限与区域配额占用，不写数据、不抛业务异常，
     * 全部越界原因收集到 violations（活动不存在仍返回 404）。
     */
    public MigrationPreviewResponse preview(MigrationItemInput.Preview request) {
        ReleaseOrder order = releaseRepository.findById(request.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND",
                        "投放活动不存在: " + request.releaseId()));

        List<String> violations = new ArrayList<>();
        List<Cohort> cohorts = cohortRepository.findCohortsByRelease(request.releaseId());
        Map<Long, Cohort> cohortById = new HashMap<>();
        cohorts.forEach(c -> cohortById.put(c.id(), c));
        Map<String, CohortRegion> regionByCode = new HashMap<>();
        cohortRepository.findRegionsByRelease(request.releaseId())
                .forEach(r -> regionByCode.put(r.regionCode(), r));
        Map<Long, Integer> currentCounts = cohortRepository.countAssignmentsByReleaseGrouped(request.releaseId());

        if (order.status() == ReleaseStatus.CANCELLED) {
            violations.add("投放活动已结束，不能迁移");
        }
        checkDuplicateDevices(request.items(), violations);

        // 仅对通过基础校验的设备计入后态移动
        Map<Long, Integer> afterCounts = new HashMap<>(currentCounts);
        Set<Long> affected = new HashSet<>();
        Set<String> validDeviceKeys = new HashSet<>();
        for (MigrationItemInput item : request.items()) {
            Cohort from = cohortById.get(item.currentCohortId());
            Cohort to = cohortById.get(item.targetCohortId());
            boolean itemValid = validateItemSoft(request.releaseId(), item, from, to, order, violations,
                    validDeviceKeys);
            if (from != null) {
                affected.add(from.id());
            }
            if (to != null) {
                affected.add(to.id());
            }
            if (itemValid) {
                afterCounts.merge(from.id(), -1, Integer::sum);
                afterCounts.merge(to.id(), 1, Integer::sum);
            }
        }

        List<MigrationPreviewResponse.CohortAfterState> states = new ArrayList<>();
        for (Long cohortId : affected) {
            Cohort cohort = cohortById.get(cohortId);
            int before = currentCounts.getOrDefault(cohortId, 0);
            int after = Math.max(0, afterCounts.getOrDefault(cohortId, 0));
            int canaryLimit = canaryLimit(cohort);
            CohortRegion region = regionByCode.get(cohort.regionCode());
            int quota = region == null ? 0 : region.quota();
            int regionBefore = sumRegion(cohort.regionCode(), cohorts, currentCounts, cohortById);
            int regionAfter = sumRegion(cohort.regionCode(), cohorts, afterCounts, cohortById);
            boolean capOk = after <= cohort.deviceCap();
            boolean canaryOk = after <= canaryLimit;
            boolean regionOk = region != null && regionAfter <= quota;
            if (!capOk) {
                violations.add("队列 " + cohort.cohortCode() + " 后态设备数 " + after
                        + " 超过设备上限 " + cohort.deviceCap());
            }
            if (!canaryOk) {
                violations.add("队列 " + cohort.cohortCode() + " 后态设备数 " + after
                        + " 超过灰度上限 " + canaryLimit);
            }
            if (!regionOk) {
                violations.add("区域 " + cohort.regionCode() + " 后态设备数 " + regionAfter
                        + " 超过区域配额 " + quota);
            }
            states.add(new MigrationPreviewResponse.CohortAfterState(cohort.id(), cohort.cohortCode(),
                    cohort.firmwareVersion(), cohort.regionCode(), cohort.deviceCap(), cohort.canaryPercent(),
                    before, after, canaryLimit, quota, regionBefore, regionAfter, capOk, canaryOk, regionOk));
        }
        states.sort(java.util.Comparator.comparingLong(MigrationPreviewResponse.CohortAfterState::cohortId));
        return new MigrationPreviewResponse(request.migrationKey(), request.releaseId(),
                violations.isEmpty(), List.copyOf(violations), states);
    }

    /**
     * 激活：requestId 同参（设备项换序视为同参）重放首次快照，异参 409，失败不占键；migrationKey 唯一。
     */
    public MigrationActivateResponse activate(MigrationItemInput.Activate request) {
        // 设备项按 deviceId 排序后生成指纹，设备项换序视为同参
        List<MigrationItemInput> sorted = request.items().stream()
                .sorted(java.util.Comparator.comparing(MigrationItemInput::deviceId))
                .toList();
        String fingerprint = "cohort.migration.activate|" + request.migrationKey() + "|"
                + request.releaseId() + "|" + fingerprintItems(sorted);
        return idempotency.execute(request.requestId(), "cohort.migration.activate", fingerprint, () -> {
            long migrationId;
            try {
                migrationId = migrationRepository.insertOrder(request.migrationKey(), request.releaseId(),
                        request.items().size(), Instant.now(clock).toString());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("MIGRATION_KEY_EXISTS",
                        "migrationKey 已存在: " + request.migrationKey());
            }
            return doActivate(migrationId, request);
        }, MigrationActivateResponse.class);
    }

    /**
     * 在已持有迁移单的同一事务内重读全部状态、硬校验完整后态并原子执行整单迁移。
     */
    private MigrationActivateResponse doActivate(long migrationId, MigrationItemInput.Activate request) {
        long releaseId = request.releaseId();
        ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "投放活动不存在: " + releaseId));
        if (order.status() == ReleaseStatus.CANCELLED) {
            throw ApiException.conflict("RELEASE_ENDED", "投放活动已结束，不能迁移");
        }

        List<Cohort> cohorts = cohortRepository.findCohortsByReleaseForUpdate(releaseId);
        Map<Long, Cohort> cohortById = new HashMap<>();
        cohorts.forEach(c -> cohortById.put(c.id(), c));
        Map<String, CohortRegion> regionByCode = new HashMap<>();
        cohortRepository.findRegionsByReleaseForUpdate(releaseId)
                .forEach(r -> regionByCode.put(r.regionCode(), r));

        checkDuplicateDevicesHard(request.items());

        List<ResolvedDevice> resolved = new ArrayList<>();
        for (MigrationItemInput item : request.items()) {
            CohortAssignment assignment = assignmentRepository.findAssignmentForUpdate(releaseId, item.deviceId())
                    .orElseThrow(() -> ApiException.unprocessable("DEVICE_NOT_ASSIGNED",
                            "设备在该活动内无分配: " + item.deviceId()));
            if (assignment.installConfirmed()) {
                throw ApiException.unprocessable("INSTALL_CONFIRMED",
                        "设备已确认安装成功，不得迁移: " + item.deviceId());
            }
            if (assignment.cohortId() != item.currentCohortId()) {
                throw ApiException.conflict("ASSIGNMENT_COHORT_CONFLICT",
                        "设备 " + item.deviceId() + " 当前队列已变化，实际: " + assignment.cohortId()
                                + "，提交: " + item.currentCohortId());
            }
            if (assignment.assignmentVersion() != item.assignmentVersion()) {
                throw ApiException.conflict("ASSIGNMENT_VERSION_CONFLICT",
                        "设备 " + item.deviceId() + " assignmentVersion 已变化，实际: "
                                + assignment.assignmentVersion() + "，提交: " + item.assignmentVersion());
            }
            Cohort from = cohortById.get(item.currentCohortId());
            if (from == null) {
                throw ApiException.unprocessable("COHORT_NOT_FOUND",
                        "源队列不存在或不属于该活动: " + item.currentCohortId());
            }
            Cohort to = cohortById.get(item.targetCohortId());
            if (to == null) {
                throw ApiException.unprocessable("TARGET_COHORT_NOT_APPLICABLE",
                        "目标队列不存在或不属于该活动: " + item.targetCohortId());
            }
            if (from.id() == to.id()) {
                throw ApiException.unprocessable("TARGET_SAME_AS_CURRENT",
                        "目标队列与当前队列相同: " + to.cohortCode());
            }
            if (!from.firmwareVersion().equals(to.firmwareVersion())) {
                throw ApiException.unprocessable("FIRMWARE_MISMATCH",
                        "目标队列固件版本 " + to.firmwareVersion() + " 与源队列 " + from.firmwareVersion()
                                + " 不一致，设备 " + item.deviceId());
            }
            AssignmentCommand pending = assignmentRepository
                    .findPendingCommandForUpdate(releaseId, item.deviceId()).orElse(null);
            resolved.add(new ResolvedDevice(item, assignment, from, to, pending));
        }

        // 完整后态计数：基于锁定的当前分配，净迁出/净迁入
        Map<Long, Integer> currentCounts = cohortRepository.countAssignmentsByReleaseGrouped(releaseId);
        Map<Long, Integer> afterCounts = new HashMap<>(currentCounts);
        for (ResolvedDevice device : resolved) {
            afterCounts.merge(device.from.id(), -1, Integer::sum);
            afterCounts.merge(device.to.id(), 1, Integer::sum);
        }
        for (Cohort cohort : cohorts) {
            if (!affected(cohort.id(), resolved)) {
                continue;
            }
            int after = afterCounts.getOrDefault(cohort.id(), 0);
            CohortService.validateCohortCapacity(cohort, after, "迁移");
            CohortRegion region = regionByCode.get(cohort.regionCode());
            if (region == null) {
                throw ApiException.unprocessable("REGION_NOT_FOUND",
                        "队列所属区域缺失配额定义: " + cohort.regionCode());
            }
            int regionAfter = sumRegion(cohort.regionCode(), cohorts, afterCounts, cohortById);
            if (regionAfter > region.quota()) {
                throw ApiException.unprocessable("REGION_QUOTA_EXCEEDED",
                        "迁移后区域 " + cohort.regionCode() + " 设备数 " + regionAfter
                                + " 超过配额 " + region.quota());
            }
        }

        // 全部校验通过后才执行变更：整单原子，任一失败已在上方抛出并随事务回滚
        List<MigrationActivateResponse.ItemResult> itemResults = new ArrayList<>();
        for (ResolvedDevice device : resolved) {
            Long supersededId = null;
            if (device.pending != null) {
                assignmentRepository.supersedePending(releaseId, device.item.deviceId());
                supersededId = device.pending.id();
            }
            int updated = assignmentRepository.applyMigration(releaseId, device.item.deviceId(),
                    device.to.id(), device.item.assignmentVersion());
            if (updated != 1) {
                // 行锁内版本被改动的兜底：整单失败回滚，不产生部分迁移
                throw ApiException.conflict("ASSIGNMENT_VERSION_CONFLICT",
                        "设备 assignmentVersion 在提交时变化: " + device.item.deviceId());
            }
            int newGeneration = device.assignment.currentGeneration() + 1;
            long newCommandId = assignmentRepository.insertCommand(releaseId, device.item.deviceId(),
                    device.to.id(), newGeneration, migrationId);
            migrationRepository.insertItem(migrationId, device.item.deviceId(), device.from.id(),
                    device.to.id(), device.item.assignmentVersion(), device.assignment.currentGeneration(),
                    newGeneration, supersededId, newCommandId);
            itemResults.add(new MigrationActivateResponse.ItemResult(device.item.deviceId(),
                    device.from.id(), device.to.id(), device.assignment.currentGeneration(), newGeneration,
                    supersededId, newCommandId));
        }
        itemResults.sort(java.util.Comparator.comparing(MigrationActivateResponse.ItemResult::deviceId));

        List<MigrationPreviewResponse.CohortAfterState> states = buildAfterStates(cohorts, cohortById,
                regionByCode, currentCounts, afterCounts);
        return new MigrationActivateResponse(migrationId, request.migrationKey(), releaseId,
                request.items().size(), Instant.now(clock).toString(), itemResults, states);
    }

    /**
     * 只读查询：迁移前后队列、每设备指令代次，以及提交后到达的旧代次 LATE 回执证据。
     */
    public MigrationDetailResponse get(long migrationId) {
        MigrationOrder order = migrationRepository.findById(migrationId)
                .orElseThrow(() -> ApiException.notFound("MIGRATION_NOT_FOUND",
                        "迁移单不存在: " + migrationId));
        List<MigrationItem> items = migrationRepository.findItemsByMigration(migrationId);
        var late = assignmentRepository.findLateHistoryByMigration(migrationId);
        return MigrationDetailResponse.of(order, items, late);
    }

    // ---- 预览软校验与后态辅助 ----

    private boolean validateItemSoft(long releaseId, MigrationItemInput item, Cohort from, Cohort to,
                                     ReleaseOrder order, List<String> violations, Set<String> validDeviceKeys) {
        boolean valid = true;
        if (order.status() == ReleaseStatus.CANCELLED) {
            valid = false;
        }
        CohortAssignment assignment = assignmentRepository.findAssignment(releaseId, item.deviceId())
                .orElse(null);
        if (assignment == null) {
            violations.add("设备 " + item.deviceId() + " 在该活动内无分配");
            return false;
        }
        if (assignment.installConfirmed()) {
            violations.add("设备 " + item.deviceId() + " 已确认安装成功，不得迁移");
            valid = false;
        }
        if (assignment.cohortId() != item.currentCohortId()) {
            violations.add("设备 " + item.deviceId() + " 提交的当前队列 " + item.currentCohortId()
                    + " 与实际 " + assignment.cohortId() + " 不一致");
            valid = false;
        }
        if (assignment.assignmentVersion() != item.assignmentVersion()) {
            violations.add("设备 " + item.deviceId() + " 提交的 assignmentVersion " + item.assignmentVersion()
                    + " 与实际 " + assignment.assignmentVersion() + " 不一致");
            valid = false;
        }
        if (from == null) {
            violations.add("源队列不存在或不属于该活动: " + item.currentCohortId());
            valid = false;
        }
        if (to == null) {
            violations.add("目标队列不存在或不属于该活动: " + item.targetCohortId());
            valid = false;
        }
        if (from != null && to != null) {
            if (from.id() == to.id()) {
                violations.add("设备 " + item.deviceId() + " 目标队列与当前队列相同");
                valid = false;
            } else if (!from.firmwareVersion().equals(to.firmwareVersion())) {
                violations.add("设备 " + item.deviceId() + " 源/目标队列固件版本不一致（"
                        + from.firmwareVersion() + " -> " + to.firmwareVersion() + "）");
                valid = false;
            }
        }
        if (!validDeviceKeys.add(item.deviceId())) {
            // 重复设备由 checkDuplicateDevices 统一报告，此处不计入移动
            valid = false;
        }
        return valid;
    }

    private void checkDuplicateDevices(List<MigrationItemInput> items, List<String> violations) {
        Set<String> seen = new HashSet<>();
        for (MigrationItemInput item : items) {
            if (!seen.add(item.deviceId())) {
                violations.add("设备在同一迁移单中重复出现: " + item.deviceId());
            }
        }
    }

    private void checkDuplicateDevicesHard(List<MigrationItemInput> items) {
        Set<String> seen = new HashSet<>();
        for (MigrationItemInput item : items) {
            if (!seen.add(item.deviceId())) {
                throw ApiException.unprocessable("DUPLICATE_DEVICE",
                        "设备在同一迁移单中重复出现: " + item.deviceId());
            }
        }
    }

    private boolean affected(long cohortId, List<ResolvedDevice> resolved) {
        return resolved.stream().anyMatch(d -> d.from.id() == cohortId || d.to.id() == cohortId);
    }

    private List<MigrationPreviewResponse.CohortAfterState> buildAfterStates(
            List<Cohort> cohorts, Map<Long, Cohort> cohortById, Map<String, CohortRegion> regionByCode,
            Map<Long, Integer> currentCounts, Map<Long, Integer> afterCounts) {
        List<MigrationPreviewResponse.CohortAfterState> states = new ArrayList<>();
        for (Cohort cohort : cohorts) {
            int before = currentCounts.getOrDefault(cohort.id(), 0);
            int after = afterCounts.getOrDefault(cohort.id(), 0);
            int canaryLimit = canaryLimit(cohort);
            CohortRegion region = regionByCode.get(cohort.regionCode());
            int quota = region == null ? 0 : region.quota();
            int regionBefore = sumRegion(cohort.regionCode(), cohorts, currentCounts, cohortById);
            int regionAfter = sumRegion(cohort.regionCode(), cohorts, afterCounts, cohortById);
            states.add(new MigrationPreviewResponse.CohortAfterState(cohort.id(), cohort.cohortCode(),
                    cohort.firmwareVersion(), cohort.regionCode(), cohort.deviceCap(), cohort.canaryPercent(),
                    before, after, canaryLimit, quota, regionBefore, regionAfter,
                    after <= cohort.deviceCap(), after <= canaryLimit, region != null && regionAfter <= quota));
        }
        // 仅保留规模或区域占用发生变化的受影响队列
        states = states.stream().filter(s -> s.beforeCount() != s.afterCount()
                || s.regionUsedBefore() != s.regionUsedAfter()).toList();
        return states;
    }

    private int sumRegion(String regionCode, List<Cohort> cohorts, Map<Long, Integer> counts,
                          Map<Long, Cohort> cohortById) {
        int sum = 0;
        for (Cohort cohort : cohorts) {
            if (cohort.regionCode().equals(regionCode)) {
                sum += Math.max(0, counts.getOrDefault(cohort.id(), 0));
            }
        }
        return sum;
    }

    private int canaryLimit(Cohort cohort) {
        return (cohort.deviceCap() * cohort.canaryPercent() + 99) / 100;
    }

    private String fingerprintItems(List<MigrationItemInput> sorted) {
        StringBuilder sb = new StringBuilder();
        for (MigrationItemInput item : sorted) {
            sb.append(item.deviceId()).append('|')
                    .append(item.currentCohortId()).append('|')
                    .append(item.assignmentVersion()).append('|')
                    .append(item.targetCohortId()).append(';');
        }
        return sb.toString();
    }

    /**
     * 激活事务内解析后的单设备迁移上下文。
     */
    private record ResolvedDevice(MigrationItemInput item, CohortAssignment assignment, Cohort from, Cohort to,
                                  AssignmentCommand pending) {
    }
}
