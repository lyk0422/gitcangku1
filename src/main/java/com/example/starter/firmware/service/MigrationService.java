package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ActivateMigrationRequest;
import com.example.starter.firmware.api.CohortPostStateView;
import com.example.starter.firmware.api.LateReceiptView;
import com.example.starter.firmware.api.MigrationItemRequest;
import com.example.starter.firmware.api.MigrationItemView;
import com.example.starter.firmware.api.MigrationPreviewView;
import com.example.starter.firmware.api.MigrationView;
import com.example.starter.firmware.api.PreviewItemView;
import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortStatus;
import com.example.starter.firmware.domain.DeviceAssignment;
import com.example.starter.firmware.domain.DispatchCommand;
import com.example.starter.firmware.domain.InstallStatus;
import com.example.starter.firmware.domain.MigrationItem;
import com.example.starter.firmware.domain.MigrationOrder;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CampaignRepository;
import com.example.starter.firmware.repo.CohortReceiptRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.repo.DispatchCommandRepository;
import com.example.starter.firmware.repo.MigrationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 队列迁移单：预览（只读，按完整后态计算）、激活（单事务整单生效）、查询（只读）。
 * 激活在活动行锁内重新读取设备分配、活动状态、队列策略与未决指令；
 * 未决指令原子标记 SUPERSEDED 并为目标队列签发新代次指令，无未决指令也只递增代次。
 * 任一设备校验失败或完整后态越界，整单回滚，不迁移部分设备、不提前废弃指令。
 */
@Service
public class MigrationService {

    /**
     * 单台设备的迁移计划：源/目标队列、当前分配与（可选）未决指令。
     */
    private record PlannedItem(DeviceAssignment assignment, Cohort fromCohort, Cohort toCohort,
                               DispatchCommand pendingCommand) {
    }

    private final CampaignRepository campaignRepository;
    private final CohortRepository cohortRepository;
    private final AssignmentRepository assignmentRepository;
    private final DispatchCommandRepository commandRepository;
    private final CohortReceiptRepository receiptRepository;
    private final MigrationRepository migrationRepository;
    private final CampaignService campaignService;
    private final IdempotencyService idempotency;

    public MigrationService(CampaignRepository campaignRepository, CohortRepository cohortRepository,
                            AssignmentRepository assignmentRepository,
                            DispatchCommandRepository commandRepository,
                            CohortReceiptRepository receiptRepository,
                            MigrationRepository migrationRepository,
                            CampaignService campaignService, IdempotencyService idempotency) {
        this.campaignRepository = campaignRepository;
        this.cohortRepository = cohortRepository;
        this.assignmentRepository = assignmentRepository;
        this.commandRepository = commandRepository;
        this.receiptRepository = receiptRepository;
        this.migrationRepository = migrationRepository;
        this.campaignService = campaignService;
        this.idempotency = idempotency;
    }

    /**
     * 预览：与激活相同的校验与完整后态计算，但不写任何数据。
     */
    public MigrationPreviewView preview(long campaignId, List<MigrationItemRequest> items) {
        campaignService.findCampaign(campaignId);
        List<MigrationItemRequest> sorted = sortedItems(items);
        List<PlannedItem> plan = planMigration(campaignId, sorted, false);
        long totalAssigned = assignmentRepository.countByCampaign(campaignId);
        Map<Long, Cohort> affected = affectedCohorts(plan);
        Map<Long, Integer> postSizes = postSizes(affected, plan);
        List<PreviewItemView> itemViews = plan.stream()
                .map(p -> new PreviewItemView(p.assignment().deviceId(), p.fromCohort().id(),
                        p.toCohort().id(), p.assignment().assignmentGeneration(),
                        p.assignment().assignmentGeneration() + 1))
                .toList();
        List<CohortPostStateView> cohortViews = affected.values().stream()
                .map(c -> new CohortPostStateView(c.id(), c.code(), c.deviceCount(),
                        postSizes.get(c.id()), c.deviceCap(), c.regionQuota(), c.grayPercent(),
                        totalAssigned))
                .toList();
        return new MigrationPreviewView(campaignId, itemViews, cohortViews);
    }

    /**
     * 激活：requestId 同参（设备项换序视为同参）重放首次快照，异参 409，失败不占键；
     * migrationKey 全局唯一。整单在一个事务内校验并生效。
     */
    public MigrationView activate(long campaignId, ActivateMigrationRequest request) {
        String fingerprint = fingerprint(campaignId, request.migrationKey(), request.items());
        return idempotency.execute(request.requestId(), "campaign.migration.activate", fingerprint,
                () -> {
                    campaignService.lockCampaign(campaignId);
                    if (migrationRepository.findOrderByKey(request.migrationKey()).isPresent()) {
                        throw ApiException.conflict("MIGRATION_KEY_EXISTS",
                                "migrationKey 已存在: " + request.migrationKey());
                    }
                    List<MigrationItemRequest> sorted = sortedItems(request.items());
                    List<PlannedItem> plan = planMigration(campaignId, sorted, true);
                    long migrationId;
                    try {
                        migrationId = migrationRepository.insertOrder(request.migrationKey(),
                                campaignId, plan.size(), request.requestId());
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("MIGRATION_KEY_EXISTS",
                                "migrationKey 已存在: " + request.migrationKey());
                    }
                    List<MigrationItem> applied = new ArrayList<>();
                    for (PlannedItem p : plan) {
                        Long supersededId = null;
                        Long newCommandId = null;
                        if (p.pendingCommand() != null) {
                            commandRepository.supersedeIfPending(p.pendingCommand().id());
                            supersededId = p.pendingCommand().id();
                        }
                        assignmentRepository.migrate(campaignId, p.assignment().deviceId(),
                                p.toCohort().id());
                        int newGeneration = p.assignment().assignmentGeneration() + 1;
                        if (p.pendingCommand() != null) {
                            newCommandId = commandRepository.insertPending(campaignId,
                                    p.assignment().deviceId(), p.toCohort().id(), newGeneration);
                        }
                        cohortRepository.adjustDeviceCount(p.fromCohort().id(), -1);
                        cohortRepository.adjustDeviceCount(p.toCohort().id(), 1);
                        migrationRepository.insertItem(migrationId, p.assignment().deviceId(),
                                p.fromCohort().id(), p.toCohort().id(),
                                p.assignment().assignmentGeneration(), newGeneration,
                                supersededId, newCommandId);
                        applied.add(new MigrationItem(0, migrationId, p.assignment().deviceId(),
                                p.fromCohort().id(), p.toCohort().id(),
                                p.assignment().assignmentGeneration(), newGeneration,
                                supersededId, newCommandId));
                    }
                    MigrationOrder order = migrationRepository.findOrderByKey(request.migrationKey())
                            .orElseThrow();
                    return toView(order, applied);
                }, MigrationView.class);
    }

    /**
     * 查询：返回迁移前后队列、指令代次与迟到回执证据，只读。
     */
    public MigrationView getByKey(long campaignId, String migrationKey) {
        campaignService.findCampaign(campaignId);
        MigrationOrder order = migrationRepository.findOrderByKey(migrationKey)
                .filter(o -> o.campaignId() == campaignId)
                .orElseThrow(() -> ApiException.notFound("MIGRATION_NOT_FOUND",
                        "迁移单不存在: " + migrationKey));
        return toView(order, migrationRepository.findItems(order.id()));
    }

    private MigrationView toView(MigrationOrder order, List<MigrationItem> items) {
        List<MigrationItemView> itemViews = items.stream().map(MigrationItemView::of).toList();
        List<LateReceiptView> lateReceipts = items.stream()
                .flatMap(item -> receiptRepository.findLateByDeviceUpToGeneration(
                        order.campaignId(), item.deviceId(), item.fromGeneration()).stream())
                .distinct()
                .map(LateReceiptView::of)
                .toList();
        return new MigrationView(order.migrationKey(), order.campaignId(), order.status(),
                order.deviceCount(), itemViews, lateReceipts);
    }

    /**
     * 设备项换序视为同参：指纹按 deviceId 排序后拼接。
     */
    private String fingerprint(long campaignId, String migrationKey,
                               List<MigrationItemRequest> items) {
        List<String> parts = new ArrayList<>();
        parts.add("campaign.migration.activate");
        parts.add(String.valueOf(campaignId));
        parts.add(migrationKey);
        for (MigrationItemRequest item : sortedItems(items)) {
            parts.add(String.join("#", item.deviceId(), String.valueOf(item.currentCohortId()),
                    String.valueOf(item.assignmentVersion()), String.valueOf(item.targetCohortId())));
        }
        return String.join("|", parts);
    }

    /**
     * 按 deviceId 排序并校验设备项不重复。
     */
    private List<MigrationItemRequest> sortedItems(List<MigrationItemRequest> items) {
        List<MigrationItemRequest> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(MigrationItemRequest::deviceId));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).deviceId().equals(sorted.get(i - 1).deviceId())) {
                throw ApiException.badRequest("DUPLICATE_DEVICE_ITEM",
                        "设备项重复: " + sorted.get(i).deviceId());
            }
        }
        return sorted;
    }

    /**
     * 迁移计划：逐台重新读取分配与队列策略并校验，再按完整后态校验配额。
     * forUpdate 为 true 时分配行加锁（激活路径，调用方已持有活动行锁）。
     */
    private List<PlannedItem> planMigration(long campaignId, List<MigrationItemRequest> items,
                                            boolean forUpdate) {
        var campaign = forUpdate ? campaignService.lockCampaign(campaignId)
                : campaignService.findCampaign(campaignId);
        CampaignService.requireActive(campaign);
        List<PlannedItem> plan = new ArrayList<>();
        for (MigrationItemRequest item : items) {
            Optional<DeviceAssignment> found = forUpdate
                    ? assignmentRepository.findForUpdate(campaignId, item.deviceId())
                    : assignmentRepository.find(campaignId, item.deviceId());
            DeviceAssignment assignment = found.orElseThrow(
                    () -> ApiException.notFound("ASSIGNMENT_NOT_FOUND",
                            "设备未入组: " + item.deviceId()));
            if (assignment.cohortId() != item.currentCohortId()) {
                throw ApiException.conflict("COHORT_MISMATCH",
                        "设备 " + item.deviceId() + " 当前队列为 " + assignment.cohortId()
                                + "，与提交的 " + item.currentCohortId() + " 不一致");
            }
            if (assignment.assignmentVersion() != item.assignmentVersion()) {
                throw ApiException.conflict("ASSIGNMENT_VERSION_CONFLICT",
                        "设备 " + item.deviceId() + " 分配版本已变为 "
                                + assignment.assignmentVersion());
            }
            if (assignment.installStatus() == InstallStatus.SUCCESS) {
                throw ApiException.unprocessable("DEVICE_ALREADY_INSTALLED",
                        "设备已确认安装成功，不得迁移: " + item.deviceId());
            }
            if (item.currentCohortId().equals(item.targetCohortId())) {
                throw ApiException.unprocessable("SAME_COHORT",
                        "目标队列与当前队列相同: " + item.deviceId());
            }
            Cohort fromCohort = campaignService.findCohort(assignment.cohortId());
            Cohort toCohort = cohortRepository.findByCampaignAndId(campaignId, item.targetCohortId())
                    .orElseThrow(() -> ApiException.unprocessable("TARGET_COHORT_NOT_APPLICABLE",
                            "目标队列不存在于本活动: " + item.targetCohortId()));
            if (toCohort.status() != CohortStatus.ACTIVE) {
                throw ApiException.unprocessable("TARGET_COHORT_NOT_APPLICABLE",
                        "目标队列已暂停: " + toCohort.code());
            }
            if (!fromCohort.firmwareVersion().equals(toCohort.firmwareVersion())) {
                throw ApiException.unprocessable("FIRMWARE_VERSION_MISMATCH",
                        "目标队列固件版本 " + toCohort.firmwareVersion()
                                + " 与源队列 " + fromCohort.firmwareVersion() + " 不一致");
            }
            DispatchCommand pending = forUpdate
                    ? commandRepository.findPending(campaignId, item.deviceId()).orElse(null)
                    : null;
            plan.add(new PlannedItem(assignment, fromCohort, toCohort, pending));
        }
        long totalAssigned = assignmentRepository.countByCampaign(campaignId);
        Map<Long, Cohort> affected = affectedCohorts(plan);
        Map<Long, Integer> postSizes = postSizes(affected, plan);
        for (Cohort cohort : affected.values()) {
            CampaignService.checkQuota(cohort, postSizes.get(cohort.id()), totalAssigned);
        }
        return plan;
    }

    private Map<Long, Cohort> affectedCohorts(List<PlannedItem> plan) {
        Map<Long, Cohort> affected = new LinkedHashMap<>();
        for (PlannedItem p : plan) {
            affected.putIfAbsent(p.fromCohort().id(), p.fromCohort());
            affected.putIfAbsent(p.toCohort().id(), p.toCohort());
        }
        return affected;
    }

    private Map<Long, Integer> postSizes(Map<Long, Cohort> affected, List<PlannedItem> plan) {
        Map<Long, Integer> sizes = new LinkedHashMap<>();
        affected.forEach((id, cohort) -> sizes.put(id, cohort.deviceCount()));
        for (PlannedItem p : plan) {
            sizes.merge(p.fromCohort().id(), -1, Integer::sum);
            sizes.merge(p.toCohort().id(), 1, Integer::sum);
        }
        return sizes;
    }
}
