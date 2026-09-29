package com.example.starter.plan.service;

import com.example.starter.plan.model.BlockClearanceAction;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.WorkBlock;
import com.example.starter.plan.model.WorkBlockAction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.WorkBlockRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.BlockClearanceRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RegisterWorkBlockRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.WorkBlockActionView;
import com.example.starter.plan.web.dto.WorkBlockClearanceView;
import com.example.starter.plan.web.dto.WorkBlockDetailResponse;
import com.example.starter.plan.web.dto.WorkBlockResponse;
import com.example.starter.plan.web.dto.WorkBlockView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 区段维护封锁业务：登记封锁（同事务撤销/改签全部相交已发布计划，全部完成后封锁才生效）、
 * 封锁生效后的批量原子改签与查询。
 *
 * <p>窗口与占用一律按 UTC 左闭右开比较，相邻端点不冲突。登记、批量改签与计划发布/改签/恢复
 * 共用全局发布锁串行化，配合封锁窗口冲突裁决，保证并发下交叉窗口只能有一方占用。
 * 写操作按 (操作类型, requestKey) 幂等：同键同参返回首次结果，同键换区段、窗口或计划集合返回 409。
 */
@Service
public class WorkBlockService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_WORK_BLOCK = "WORKBLOCK";
    private static final String OP_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";

    private final PlanRepository planRepo;
    private final WorkBlockRepository blockRepo;
    private final IdempotencyRepository idemRepo;
    private final PlanService planService;
    private final SlotConflictSupport conflicts;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public WorkBlockService(PlanRepository planRepo, WorkBlockRepository blockRepo,
                            IdempotencyRepository idemRepo, PlanService planService,
                            SlotConflictSupport conflicts, ObjectMapper objectMapper,
                            PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.blockRepo = blockRepo;
        this.idemRepo = idemRepo;
        this.planService = planService;
        this.conflicts = conflicts;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记区段维护封锁：全局发布锁内找出同区段同运营日与窗口相交的全部已发布计划，
     * 请求必须恰好列出这些计划并在同一事务内全部撤销或整单改签到不相交窗口；
     * 任一计划未处理、改签后仍占用封锁窗口或存在其他时隙冲突，整体回滚（封锁不生效）。
     */
    public WorkBlockResponse registerBlock(RegisterWorkBlockRequest req) {
        validateWindow(req.opDate(), req.startUtc(), req.endUtc());
        validateClearanceParams(req.clearances());
        String hash = hashRegister(req);
        Optional<WorkBlockResponse> replay =
                replayIfPresent(OP_WORK_BLOCK, req.requestKey(), hash, WorkBlockResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 拿到全局锁后再次检查幂等记录：并发同键重试时首个事务可能已提交，
                // 此时必须直接重放首次结果，不能基于已变化的计划状态重新裁决。
                Optional<WorkBlockResponse> lockedReplay =
                        replayIfPresent(OP_WORK_BLOCK, req.requestKey(), hash, WorkBlockResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                if (blockRepo.findByKey(req.blockKey()).isPresent()) {
                    throw conflict("BLOCK_KEY_EXISTS", "封锁键已存在: " + req.blockKey());
                }
                // 锁内重新枚举相交的已发布计划，保证与并发发布按提交顺序裁决
                List<DayPlan> intersecting = planRepo.findPublishedPlansOccupying(
                        req.opDate(), req.sectionId(), req.startUtc(), req.endUtc(), List.of());
                List<PendingClearance> pending = prepareClearances(req.opDate(), req.clearances(),
                        intersecting);
                // 新草稿集合的跨计划冲突与对封锁窗口的避让必须先全部裁决通过
                validateNewDrafts(req.sectionId(), req.startUtc(), req.endUtc(), pending);

                long now = System.currentTimeMillis();
                long blockId = blockRepo.insertBlock(req.blockKey(), req.opDate(), req.sectionId(),
                        req.startUtc(), req.endUtc(), now);
                List<WorkBlockActionView> views = new ArrayList<>();
                for (PendingClearance pc : pending) {
                    Long successorId = null;
                    String newKey = null;
                    if (pc.reschedule()) {
                        planService.applyRescheduleCommit(pc.oldPlan(), pc.newPlan(), now);
                        successorId = pc.newPlan().id();
                        newKey = pc.newPlan().scheduleKey();
                        blockRepo.insertAction(blockId, pc.oldPlan().id(),
                                BlockClearanceAction.RESCHEDULED, successorId, now);
                    } else {
                        planRepo.updateStatus(pc.oldPlan().id(), PlanStatus.CANCELLED, now);
                        blockRepo.insertAction(blockId, pc.oldPlan().id(),
                                BlockClearanceAction.CANCELLED, null, now);
                    }
                    views.add(new WorkBlockActionView(pc.oldPlan().scheduleKey(),
                            pc.reschedule() ? BlockClearanceAction.RESCHEDULED.name()
                                    : BlockClearanceAction.CANCELLED.name(),
                            newKey));
                }
                WorkBlockResponse response = new WorkBlockResponse(
                        new WorkBlockView(req.blockKey(), req.opDate(), req.sectionId(),
                                req.startUtc(), req.endUtc(), "ACTIVE"),
                        views);
                idemRepo.insert(OP_WORK_BLOCK, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_WORK_BLOCK, req.requestKey(), hash, WorkBlockResponse.class)
                    .orElseThrow(() -> conflict("BLOCK_KEY_EXISTS", "封锁键或幂等键冲突"));
        }
    }

    /**
     * 封锁生效后的批量改签：全部项目校验通过才统一提交；任一项仍与封锁窗口或其他已发布时隙冲突，
     * 全部计划与封锁状态保持原样（整体回滚）。
     */
    public BatchRescheduleResponse batchReschedule(BatchRescheduleRequest req) {
        for (BlockClearanceRequest c : req.clearances()) {
            if (!c.reschedule()) {
                throw badRequest("批量改签每项必须提供 newScheduleKey: " + c.scheduleKey());
            }
            if (c.expectedOldVersion() == null || c.expectedNewVersion() == null) {
                throw badRequest("批量改签每项必须提供 expectedOldVersion 与 expectedNewVersion: "
                        + c.scheduleKey());
            }
        }
        String hash = hashBatch(req);
        Optional<BatchRescheduleResponse> replay =
                replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                        BatchRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 拿到全局锁后再次检查幂等记录，理由同登记封锁
                Optional<BatchRescheduleResponse> lockedReplay =
                        replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                                BatchRescheduleResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                // 第一阶段：锁定并校验全部新旧计划，任何失败都在改写前抛出并整体回滚
                List<PendingClearance> pending = new ArrayList<>();
                for (BlockClearanceRequest c : req.clearances()) {
                    DayPlan oldPlan = planRepo.findByKeyForUpdate(c.scheduleKey())
                            .orElseThrow(() -> notFound(c.scheduleKey()));
                    DayPlan newPlan = planRepo.findByKeyForUpdate(c.newScheduleKey())
                            .orElseThrow(() -> notFound(c.newScheduleKey()));
                    assertReshapeable(oldPlan, newPlan, c);
                    pending.add(new PendingClearance(c, oldPlan, newPlan,
                            planRepo.findOccupancies(newPlan.id())));
                }
                assertDistinctPlans(pending);
                List<Long> excludeIds = new ArrayList<>();
                pending.forEach(pc -> {
                    excludeIds.add(pc.oldPlan().id());
                    excludeIds.add(pc.newPlan().id());
                });
                List<Map<String, Object>> conflictList = new ArrayList<>();
                for (PendingClearance pc : pending) {
                    conflictList.addAll(conflicts.trainOverlaps(
                            pc.newPlan().scheduleKey(), pc.occupancies()));
                    conflictList.addAll(conflicts.sectionConflicts(pc.newPlan(), pc.occupancies(),
                            excludeIds));
                    // 与全部已生效封锁窗口（含触发本次批量改签的封锁）裁决，仍冲突则 422
                    conflictList.addAll(conflicts.blockConflicts(pc.newPlan().opDate(),
                            pc.newPlan().scheduleKey(), pc.occupancies()));
                }
                conflictList.addAll(conflicts.mutualConflicts(pending.stream()
                        .map(pc -> new SlotConflictSupport.DraftGroup(
                                pc.newPlan().scheduleKey(), pc.occupancies()))
                        .toList()));
                if (!conflictList.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "批量改签存在冲突项，全部计划保持原样", conflictList);
                }
                // 第二阶段：统一改写
                long now = System.currentTimeMillis();
                List<RescheduleResponse> results = new ArrayList<>();
                for (PendingClearance pc : pending) {
                    planService.applyRescheduleCommit(pc.oldPlan(), pc.newPlan(), now);
                    results.add(new RescheduleResponse(
                            planService.loadPlanSnapshot(pc.oldPlan().scheduleKey()),
                            planService.loadPlanSnapshot(pc.newPlan().scheduleKey())));
                }
                BatchRescheduleResponse response = new BatchRescheduleResponse(results);
                idemRepo.insert(OP_BATCH_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                    BatchRescheduleResponse.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED", "幂等键冲突"));
        }
    }

    /**
     * 查询封锁详情：封锁窗口、受影响计划及每个计划在登记事务内的处理结果（历史不可变）。
     * 已撤销/改签的旧计划保留原区段和时段，后继新计划给出当前快照。
     */
    public WorkBlockDetailResponse getBlock(String blockKey) {
        WorkBlock block = blockRepo.findByKey(blockKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BLOCK_NOT_FOUND",
                        "封锁不存在: " + blockKey));
        List<WorkBlockClearanceView> clearances = new ArrayList<>();
        for (WorkBlockAction action : blockRepo.findActions(block.id())) {
            DayPlan oldPlan = planRepo.findById(action.planId())
                    .orElseThrow(() -> new IllegalStateException("处理结果对应的计划缺失: " + action));
            PlanResponse oldSnapshot = planService.loadPlanSnapshot(oldPlan.scheduleKey());
            PlanResponse newSnapshot = null;
            if (action.successorPlanId() != null) {
                DayPlan successor = planRepo.findById(action.successorPlanId())
                        .orElseThrow(() -> new IllegalStateException("改签后继计划缺失: " + action));
                newSnapshot = planService.loadPlanSnapshot(successor.scheduleKey());
            }
            clearances.add(new WorkBlockClearanceView(oldPlan.scheduleKey(), action.action().name(),
                    newSnapshot != null ? newSnapshot.scheduleKey() : null, oldSnapshot, newSnapshot));
        }
        return new WorkBlockDetailResponse(toView(block), clearances);
    }

    /**
     * 按运营日与区段列出已生效封锁，按开始时刻升序。
     */
    public List<WorkBlockView> listBlocks(LocalDate opDate, String sectionId) {
        return blockRepo.findActiveBlocks(opDate, List.of(sectionId)).stream()
                .map(this::toView)
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 校验请求的处理指令与实际相交计划集合完全一致，并锁定全部新旧计划。
     */
    private List<PendingClearance> prepareClearances(LocalDate opDate,
                                                     List<BlockClearanceRequest> requests,
                                                     List<DayPlan> intersecting) {
        Map<String, DayPlan> intersectByKey = new LinkedHashMap<>();
        for (DayPlan p : intersecting) {
            intersectByKey.put(p.scheduleKey(), p);
        }
        Set<String> requestedKeys = new LinkedHashSet<>();
        List<PendingClearance> pending = new ArrayList<>();
        List<Map<String, Object>> mismatch = new ArrayList<>();
        for (BlockClearanceRequest c : requests) {
            if (!requestedKeys.add(c.scheduleKey())) {
                throw badRequest("同一计划在处理指令中重复出现: " + c.scheduleKey());
            }
            DayPlan oldPlan = intersectByKey.get(c.scheduleKey());
            if (oldPlan == null) {
                // 不存在按 404；存在但不相交按 422 处理指令不匹配
                if (planRepo.findByKey(c.scheduleKey()).isEmpty()) {
                    throw notFound(c.scheduleKey());
                }
                planRepo.findByKeyForUpdate(c.scheduleKey());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "BLOCK_CLEARANCE_MISMATCH");
                detail.put("scheduleKey", c.scheduleKey());
                detail.put("reason", "该计划与封锁窗口不相交，不应出现在处理指令中");
                mismatch.add(detail);
                continue;
            }
            // 枚举查询未加行锁，这里对相交计划加锁并复查状态
            oldPlan = planRepo.findByKeyForUpdate(c.scheduleKey())
                    .orElseThrow(() -> notFound(c.scheduleKey()));
            DayPlan newPlan = null;
            List<Occupancy> occupancies = List.of();
            if (c.reschedule()) {
                if (c.expectedOldVersion() == null || c.expectedNewVersion() == null) {
                    throw badRequest("改签项必须提供 expectedOldVersion 与 expectedNewVersion: "
                            + c.scheduleKey());
                }
                newPlan = planRepo.findByKeyForUpdate(c.newScheduleKey())
                        .orElseThrow(() -> notFound(c.newScheduleKey()));
                if (!oldPlan.opDate().equals(newPlan.opDate())) {
                    throw conflict("OP_DATE_MISMATCH",
                            "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
                }
                if (newPlan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "改签新计划必须为草稿，当前状态: " + newPlan.status());
                }
                if (c.expectedNewVersion() != newPlan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedNewVersion=" + c.expectedNewVersion()
                                    + " 与新计划当前版本 " + newPlan.version() + " 不一致");
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + c.newScheduleKey());
                }
                occupancies = planRepo.findOccupancies(newPlan.id());
            }
            if (oldPlan.status() != PlanStatus.PUBLISHED) {
                throw conflict("PLAN_STATE_CONFLICT",
                        "仅已发布计划可在封锁登记中处理，当前状态: " + oldPlan.status());
            }
            if (c.expectedOldVersion() != null && c.expectedOldVersion() != oldPlan.version()) {
                throw conflict("VERSION_CONFLICT",
                        "expectedOldVersion=" + c.expectedOldVersion()
                                + " 与计划当前版本 " + oldPlan.version() + " 不一致");
            }
            if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                throw conflict("LINK_CONFLICT", "计划已存在直接后继: " + oldPlan.scheduleKey());
            }
            pending.add(new PendingClearance(c, oldPlan, newPlan, occupancies));
        }
        for (DayPlan p : intersecting) {
            if (!requestedKeys.contains(p.scheduleKey())) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "BLOCK_CLEARANCE_MISMATCH");
                detail.put("scheduleKey", p.scheduleKey());
                detail.put("reason", "该已发布计划与封锁窗口相交但未在本次操作中处理");
                mismatch.add(detail);
            }
        }
        if (!mismatch.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BLOCK_CLEARANCE_REQUIRED",
                    "相交计划必须全部在同一次操作中撤销或改签，封锁未生效", mismatch);
        }
        return pending;
    }

    /**
     * 校验登记内改签的新草稿：避让封锁窗口（同区段左闭右开不重叠，相邻端点允许）、
     * 列车内部与跨计划时隙冲突，以及多份新草稿之间的互相冲突。
     */
    private void validateNewDrafts(String blockSection, Instant blockStart, Instant blockEnd,
                                   List<PendingClearance> pending) {
        List<Long> excludeIds = new ArrayList<>();
        pending.forEach(pc -> {
            excludeIds.add(pc.oldPlan().id());
            if (pc.newPlan() != null) {
                excludeIds.add(pc.newPlan().id());
            }
        });
        List<Map<String, Object>> conflictList = new ArrayList<>();
        List<SlotConflictSupport.DraftGroup> draftGroups = new ArrayList<>();
        for (PendingClearance pc : pending) {
            if (!pc.reschedule()) {
                continue;
            }
            conflictList.addAll(conflicts.trainOverlaps(
                    pc.newPlan().scheduleKey(), pc.occupancies()));
            conflictList.addAll(conflicts.sectionConflicts(pc.newPlan(), pc.occupancies(),
                    excludeIds));
            // 避让此前已生效的其他封锁窗口（本次新封锁尚未插入，下面单独裁决）
            conflictList.addAll(conflicts.blockConflicts(pc.newPlan().opDate(),
                    pc.newPlan().scheduleKey(), pc.occupancies()));
            for (Occupancy o : pc.occupancies()) {
                if (o.sectionId().equals(blockSection)
                        && SlotConflictSupport.overlaps(o.startUtc(), o.endUtc(),
                        blockStart, blockEnd)) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "BLOCK_CONFLICT");
                    detail.put("sectionId", blockSection);
                    detail.put("scheduleKey", pc.newPlan().scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    detail.put("blockStartUtc", blockStart.toString());
                    detail.put("blockEndUtc", blockEnd.toString());
                    conflictList.add(detail);
                }
            }
            draftGroups.add(new SlotConflictSupport.DraftGroup(
                    pc.newPlan().scheduleKey(), pc.occupancies()));
        }
        conflictList.addAll(conflicts.mutualConflicts(draftGroups));
        if (!conflictList.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "改签后的计划仍占用封锁窗口或存在其他时隙冲突，封锁未生效", conflictList);
        }
    }

    /**
     * 批量改签上下文中的单条状态与版本校验（旧必须已发布、新必须草稿、同日、无既有关联、版本匹配）。
     */
    private void assertReshapeable(DayPlan oldPlan, DayPlan newPlan, BlockClearanceRequest c) {
        if (oldPlan.status() != PlanStatus.PUBLISHED) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
        }
        if (newPlan.status() != PlanStatus.DRAFT) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "改签新计划必须为草稿，当前状态: " + newPlan.status());
        }
        if (c.expectedOldVersion() != oldPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedOldVersion=" + c.expectedOldVersion()
                            + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
        }
        if (c.expectedNewVersion() != newPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedNewVersion=" + c.expectedNewVersion()
                            + " 与新计划当前版本 " + newPlan.version() + " 不一致");
        }
        if (!oldPlan.opDate().equals(newPlan.opDate())) {
            throw conflict("OP_DATE_MISMATCH",
                    "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
        }
        if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
            throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldPlan.scheduleKey());
        }
        if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
            throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + newPlan.scheduleKey());
        }
    }

    /**
     * 批量指令中不得出现重复的旧计划或新计划，否则互斥关系无法裁决。
     */
    private void assertDistinctPlans(List<PendingClearance> pending) {
        Set<String> oldKeys = new LinkedHashSet<>();
        Set<String> newKeys = new LinkedHashSet<>();
        for (PendingClearance pc : pending) {
            if (!oldKeys.add(pc.oldPlan().scheduleKey())
                    || !newKeys.add(pc.newPlan().scheduleKey())) {
                throw badRequest("批量改签中存在重复的旧计划或新计划");
            }
        }
    }

    private void validateClearanceParams(List<BlockClearanceRequest> clearances) {
        Set<String> keys = new LinkedHashSet<>();
        for (BlockClearanceRequest c : clearances) {
            if (!keys.add(c.scheduleKey())) {
                throw badRequest("同一计划在处理指令中重复出现: " + c.scheduleKey());
            }
            if (c.reschedule() && (c.expectedOldVersion() == null || c.expectedNewVersion() == null)) {
                throw badRequest("改签项必须提供 expectedOldVersion 与 expectedNewVersion: "
                        + c.scheduleKey());
            }
        }
    }

    /**
     * 窗口参数校验：结束晚于开始，且 [start, end) 完全落在 opDate 运营日（Asia/Shanghai）内。
     */
    private void validateWindow(LocalDate opDate, Instant startUtc, Instant endUtc) {
        if (!endUtc.isAfter(startUtc)) {
            throw badRequest("封锁结束时刻必须晚于开始时刻");
        }
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        if (startUtc.isBefore(dayStart) || endUtc.isAfter(dayEnd)) {
            throw badRequest("封锁窗口必须完全落在运营日 " + opDate + "（Asia/Shanghai）内");
        }
    }

    private WorkBlockView toView(WorkBlock block) {
        return new WorkBlockView(block.blockKey(), block.opDate(), block.sectionId(),
                block.startUtc(), block.endUtc(), block.status().name());
    }

    /**
     * 登记/批量内一条已锁定待处理的计划对（改签时 newPlan 非空）。
     */
    private record PendingClearance(BlockClearanceRequest request, DayPlan oldPlan, DayPlan newPlan,
                                    List<Occupancy> occupancies) {
        boolean reschedule() {
            return newPlan != null;
        }
    }

    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String hashRegister(RegisterWorkBlockRequest req) {
        StringBuilder sb = new StringBuilder(OP_WORK_BLOCK).append('\n')
                .append(req.blockKey()).append('\n').append(req.opDate()).append('\n')
                .append(req.sectionId()).append('\n')
                .append(req.startUtc().toEpochMilli()).append('\n')
                .append(req.endUtc().toEpochMilli());
        appendClearances(sb, req.clearances());
        return sha256(sb.toString());
    }

    private String hashBatch(BatchRescheduleRequest req) {
        StringBuilder sb = new StringBuilder(OP_BATCH_RESCHEDULE);
        appendClearances(sb, req.clearances());
        return sha256(sb.toString());
    }

    private void appendClearances(StringBuilder sb, List<BlockClearanceRequest> clearances) {
        for (BlockClearanceRequest c : clearances) {
            sb.append('\n').append(c.scheduleKey()).append('|')
                    .append(c.reschedule() ? c.newScheduleKey() : "-").append('|')
                    .append(c.expectedOldVersion()).append('|').append(c.expectedNewVersion());
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
