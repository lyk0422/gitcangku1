package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.WorkBlock;
import com.example.starter.plan.model.WorkBlockStatus;
import com.example.starter.plan.model.AffectedPlanDisposition;
import com.example.starter.plan.model.AffectedPlanRecord;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.WorkBlockRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.AffectedPlanView;
import com.example.starter.plan.web.dto.BatchRescheduleItem;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterWorkBlockRequest;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.example.starter.plan.web.dto.WorkBlockPlanAction;
import com.example.starter.plan.web.dto.WorkBlockResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布与改签经全局发布锁串行化，同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_RESTORE = "RESTORE";
    private static final String OP_WORK_BLOCK = "WORK_BLOCK";
    private static final String OP_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final WorkBlockRepository blockRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       WorkBlockRepository blockRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.blockRepo = blockRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String hash = hashCreate(req);
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                long now = System.currentTimeMillis();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT, now);
                planRepo.insertOccupancies(planId, toOccupancies(planId, req.occupancies()));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用清单，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        validateOccupancyParams(req.occupancies());
        String hash = hashUpdate(scheduleKey, req);
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改占用，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 " + plan.version() + " 不一致");
                }
                validateWithinOperationDay(req.occupancies(), plan.opDate());
                long now = System.currentTimeMillis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_UPDATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_UPDATE, req.requestKey(), hash);
        }
    }

    /**
     * 发布计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠，
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突区段与计划）。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_PUBLISH, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                conflicts.addAll(findBlockConflicts(scheduleKey, plan.opDate(), occupancies, Set.of()));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙冲突，计划保持草稿", conflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划与占用保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_CANCEL, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可取消，当前状态: " + plan.status());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.CANCELLED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CANCEL, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CANCEL, requestKey, hash);
        }
    }

    /**
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠、跨计划区段冲突与区段封锁冲突，仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用与关联均不改变。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String hash = hashReschedule(oldScheduleKey, req);
        Optional<RescheduleResponse> replay =
                replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                RescheduleResponse response = performRescheduleInTx(oldScheduleKey, req);
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response),
                        System.currentTimeMillis());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "改签前后继关联冲突"));
        }
    }

    /**
     * 恢复已撤销（CANCELLED 且无改签后继）的计划：在全局发布锁内重新校验列车内部重叠、
     * 跨计划区段冲突与区段封锁，全部通过后恢复为 PUBLISHED；任一冲突保持 CANCELLED 并返回 422。
     * 已改签取消（存在直接后继）的计划不可恢复，返回 409。
     */
    public PlanResponse restore(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_RESTORE, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_RESTORE, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<PlanResponse> lockedReplay =
                        replayIfPresent(OP_RESTORE, requestKey, hash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.CANCELLED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已撤销计划可恢复，当前状态: " + plan.status());
                }
                if (planRepo.findLinkByPredecessor(plan.id()).isPresent()) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "已改签计划不可恢复（存在直接后继）: " + scheduleKey);
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                conflicts.addAll(findBlockConflicts(scheduleKey, plan.opDate(), occupancies, Set.of()));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "恢复计划存在时隙冲突，计划保持已撤销", conflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_RESTORE, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_RESTORE, requestKey, hash);
        }
    }

    /**
     * 批量原子改签：全局发布锁内按请求顺序逐项执行改签，允许后项以前项发布的后继为旧计划形成改签链；
     * 任一项状态、版本、关联或时隙（含区段封锁）校验失败则整单回滚，
     * 全部计划与封锁状态保持原样。整单按 requestKey 幂等。
     */
    public BatchRescheduleResponse batchReschedule(BatchRescheduleRequest req) {
        Set<String> newKeys = new HashSet<>();
        for (BatchRescheduleItem item : req.plans()) {
            if (item.oldScheduleKey().equals(item.newScheduleKey())) {
                throw badRequest("新旧计划必须不同: " + item.oldScheduleKey());
            }
            if (!newKeys.add(item.newScheduleKey())) {
                throw badRequest("批量改签中后继草稿重复出现: " + item.newScheduleKey());
            }
        }
        String hash = hashBatchReschedule(req);
        Optional<BatchRescheduleResponse> replay =
                replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                        BatchRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<BatchRescheduleResponse> lockedReplay =
                        replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                                BatchRescheduleResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                List<RescheduleResponse> results = new ArrayList<>();
                for (BatchRescheduleItem item : req.plans()) {
                    results.add(performRescheduleInTx(item.oldScheduleKey(), new RescheduleRequest(
                            req.requestKey(), item.newScheduleKey(),
                            item.expectedOldVersion(), item.expectedNewVersion())));
                }
                BatchRescheduleResponse response = new BatchRescheduleResponse(List.copyOf(results));
                idemRepo.insert(OP_BATCH_RESCHEDULE, req.requestKey(), hash, toJson(response),
                        System.currentTimeMillis());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                    BatchRescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "批量改签前后继关联冲突"));
        }
    }

    /**
     * 登记区段维护封锁：全局发布锁内找出窗口相交的全部已发布计划，
     * 指令清单必须恰好覆盖它们，并在同一事务内全部撤销或整单改签至不相交窗口；
     * 遗漏、多余、重复、状态非法或后继草稿仍与封锁/其他已发布计划冲突时整单 422 回滚，封锁不生效。
     * 封锁行在提交前插入，使后继校验能看到自身窗口；回滚时封锁行一并消失。
     */
    public WorkBlockResponse registerWorkBlock(RegisterWorkBlockRequest req) {
        validateBlockWindow(req.startUtc(), req.endUtc());
        validateWorkBlockActions(req.plans());
        String hash = hashWorkBlock(req);
        Optional<WorkBlockResponse> replay =
                replayIfPresent(OP_WORK_BLOCK, req.requestKey(), hash, WorkBlockResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<WorkBlockResponse> lockedReplay =
                        replayIfPresent(OP_WORK_BLOCK, req.requestKey(), hash, WorkBlockResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                LocalDate opDate = req.startUtc().atZone(OPERATION_ZONE).toLocalDate();
                validateWindowWithinOperationDay(req.startUtc(), req.endUtc(), opDate);
                if (blockRepo.findByKey(req.blockKey()).isPresent()) {
                    throw conflict("WORK_BLOCK_KEY_EXISTS", "封锁键已存在: " + req.blockKey());
                }

                // 1. 找出登记时刻与窗口相交的全部已发布计划占用（此时尚未改动任何状态）
                List<Occupancy> intersecting = planRepo.findPublishedOccupanciesInWindow(
                        opDate, req.sectionId(), req.startUtc(), req.endUtc(), List.of());
                Map<Long, List<Occupancy>> slotsByPlan = new LinkedHashMap<>();
                for (Occupancy o : intersecting) {
                    slotsByPlan.computeIfAbsent(o.planId(), k -> new ArrayList<>()).add(o);
                }

                // 2. 指令清单不重复、动作参数齐备等结构校验在事务外已完成；
                //    此处对涉及的旧计划与后继草稿加锁并完成状态/日期/关联校验。
                Set<Long> handledPlanIds = new HashSet<>();
                List<Map<String, Object>> conflicts = new ArrayList<>();
                // 先锁定全部涉及的旧计划与后继草稿，完成状态/日期/关联校验
                Map<String, DayPlan> oldPlans = new LinkedHashMap<>();
                Map<String, DayPlan> successorDrafts = new LinkedHashMap<>();
                for (WorkBlockPlanAction action : req.plans()) {
                    DayPlan oldPlan = planRepo.findByKeyForUpdate(action.scheduleKey())
                            .orElseThrow(() -> badRequestPlan(
                                    "处理清单中的计划不存在: " + action.scheduleKey()));
                    if (oldPlan.status() != PlanStatus.PUBLISHED) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                "封锁处理清单中的计划必须为已发布状态: " + action.scheduleKey()
                                        + "，当前 " + oldPlan.status());
                    }
                    if (!oldPlan.opDate().equals(opDate)) {
                        throw badRequestPlan("处理计划运营日与封锁窗口不一致: " + action.scheduleKey());
                    }
                    oldPlans.put(action.scheduleKey(), oldPlan);
                    if ("RESCHEDULE".equals(action.action())) {
                        String newKey = action.newScheduleKey();
                        if (newKey == null || newKey.isBlank()) {
                            throw badRequestPlan("RESCHEDULE 必须提供 newScheduleKey: "
                                    + action.scheduleKey());
                        }
                        if (newKey.equals(action.scheduleKey())) {
                            throw badRequestPlan("改签新旧计划必须不同: " + newKey);
                        }
                        DayPlan newPlan = planRepo.findByKeyForUpdate(newKey)
                                .orElseThrow(() -> badRequestPlan("改签后继草稿不存在: " + newKey));
                        if (newPlan.status() != PlanStatus.DRAFT) {
                            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                    "改签后继计划必须为草稿: " + newKey + "，当前 " + newPlan.status());
                        }
                        if (!newPlan.opDate().equals(opDate)) {
                            throw badRequestPlan("改签后继草稿运营日与封锁窗口不一致: " + newKey);
                        }
                        if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                            throw conflict("LINK_CONFLICT", "后继草稿已存在直接前驱: " + newKey);
                        }
                        successorDrafts.put(action.scheduleKey(), newPlan);
                    }
                    if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                        throw conflict("LINK_CONFLICT",
                                "受影响计划已存在直接后继，不能再次改签: " + action.scheduleKey());
                    }
                }
                // 清单中的计划必须确实与窗口相交
                for (DayPlan oldPlan : oldPlans.values()) {
                    if (!slotsByPlan.containsKey(oldPlan.id())) {
                        throw badRequestPlan("处理清单中的计划与封锁窗口不相交: "
                                + oldPlan.scheduleKey());
                    }
                    handledPlanIds.add(oldPlan.id());
                }
                // 相交计划必须全部被覆盖；遗漏即登记失败，封锁不写入
                for (Long planId : slotsByPlan.keySet()) {
                    if (!handledPlanIds.contains(planId)) {
                        DayPlan missing = planRepo.findById(planId)
                                .orElseThrow(() -> new IllegalStateException("相交计划缺失: " + planId));
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "WORK_BLOCK_UNCLEARED");
                        detail.put("sectionId", req.sectionId());
                        detail.put("blockKey", req.blockKey());
                        detail.put("conflictingScheduleKey", missing.scheduleKey());
                        detail.put("startUtc", req.startUtc().toString());
                        detail.put("endUtc", req.endUtc().toString());
                        conflicts.add(detail);
                    }
                }
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在未撤销或改签的相交已发布计划，封锁未生效", conflicts);
                }

                // 3. 插入封锁行（同事务内对后继校验可见；失败随事务回滚）
                long now = System.currentTimeMillis();
                long blockId = blockRepo.insertBlock(req.blockKey(), req.sectionId(), opDate,
                        req.startUtc(), req.endUtc(), WorkBlockStatus.EFFECTIVE, now);

                // 4. 校验全部后继草稿：内部列车重叠、与第三方已发布计划冲突、
                //    与任何已生效封锁（含本封锁）冲突、后继之间互不冲突
                Set<Long> excludePlanIds = new HashSet<>();
                excludePlanIds.addAll(oldPlans.values().stream().map(DayPlan::id).toList());
                successorDrafts.values().forEach(p -> excludePlanIds.add(p.id()));
                List<Occupancy> successorOccupancies = new ArrayList<>();
                for (Map.Entry<String, DayPlan> entry : successorDrafts.entrySet()) {
                    DayPlan newPlan = entry.getValue();
                    List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                    conflicts.addAll(findTrainOverlaps(newPlan.scheduleKey(), occupancies));
                    conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                            new ArrayList<>(excludePlanIds)));
                    conflicts.addAll(findBlockConflicts(newPlan.scheduleKey(), newPlan.opDate(),
                            occupancies, Set.of()));
                    successorOccupancies.addAll(occupancies);
                }
                // 后继草稿两两之间也不得在同区段相交
                conflicts.addAll(findMutualOccupancyConflicts(successorDrafts, successorOccupancies));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "封锁处理存在时隙冲突，封锁未生效，全部计划保持原样", conflicts);
                }

                // 5. 全部校验通过：执行撤销/改签，写处理记录与原占用快照
                List<AffectedPlanView> affectedViews = new ArrayList<>();
                for (WorkBlockPlanAction action : req.plans()) {
                    DayPlan oldPlan = oldPlans.get(action.scheduleKey());
                    List<Occupancy> slots = slotsByPlan.get(oldPlan.id());
                    Long successorId = null;
                    String successorKey = null;
                    AffectedPlanDisposition disposition;
                    if ("CANCEL".equals(action.action())) {
                        disposition = AffectedPlanDisposition.CANCELLED;
                        planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                    } else {
                        disposition = AffectedPlanDisposition.RESCHEDULED;
                        DayPlan newPlan = successorDrafts.get(action.scheduleKey());
                        planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                        planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                        planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                        successorId = newPlan.id();
                        successorKey = newPlan.scheduleKey();
                    }
                    long affectedId = blockRepo.insertAffectedPlan(blockId, oldPlan.id(),
                            oldPlan.scheduleKey(), disposition, successorId, now);
                    blockRepo.insertAffectedSlots(affectedId, slots);
                    affectedViews.add(new AffectedPlanView(oldPlan.scheduleKey(), disposition.name(),
                            successorKey, slots.stream()
                                    .map(o -> new OccupancyView(o.trainNo(), o.sectionId(),
                                            o.startUtc(), o.endUtc()))
                                    .toList()));
                }

                WorkBlockResponse response = new WorkBlockResponse(req.blockKey(), req.sectionId(),
                        opDate, req.startUtc(), req.endUtc(), WorkBlockStatus.EFFECTIVE.name(),
                        List.copyOf(affectedViews));
                idemRepo.insert(OP_WORK_BLOCK, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_WORK_BLOCK, req.requestKey(), hash, WorkBlockResponse.class)
                    .orElseThrow(() -> conflict("WORK_BLOCK_KEY_EXISTS", "封锁键或幂等键冲突"));
        }
    }

    /**
     * 查询封锁详情：封锁窗口、受影响计划及处理结果（含相交原占用快照）。不存在返回 404。
     */
    public WorkBlockResponse getWorkBlock(String blockKey) {
        WorkBlock block = blockRepo.findByKey(blockKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "WORK_BLOCK_NOT_FOUND",
                        "封锁不存在: " + blockKey));
        return toWorkBlockResponse(block);
    }

    /**
     * 按运营日（必填）与区段（可选）列出已生效封锁，含各封锁的受影响计划与处理结果，
     * 按区段、封锁开始时刻升序。
     */
    public List<WorkBlockResponse> listWorkBlocks(LocalDate opDate, String sectionId) {
        List<WorkBlock> blocks;
        if (sectionId == null || sectionId.isBlank()) {
            blocks = blockRepo.findEffectiveBlocksByDate(opDate);
        } else {
            blocks = blockRepo.findEffectiveBlocks(opDate, List.of(sectionId));
        }
        return blocks.stream().map(this::toWorkBlockResponse).toList();
    }

    // ---------- 封锁/批量改签内部实现 ----------

    /**
     * 单项改签的事务内实现（不加幂等记录、不获取锁），供单笔改签与批量改签复用；
     * 调用方必须已持有全局发布锁。
     */
    private RescheduleResponse performRescheduleInTx(String oldScheduleKey, RescheduleRequest req) {
        DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                .orElseThrow(() -> notFound(oldScheduleKey));
        DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                .orElseThrow(() -> notFound(req.newScheduleKey()));
        if (oldPlan.status() != PlanStatus.PUBLISHED) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
        }
        if (newPlan.status() != PlanStatus.DRAFT) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "改签新计划必须为草稿，当前状态: " + newPlan.status());
        }
        if (req.expectedOldVersion() != oldPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedOldVersion=" + req.expectedOldVersion()
                            + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
        }
        if (req.expectedNewVersion() != newPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedNewVersion=" + req.expectedNewVersion()
                            + " 与新计划当前版本 " + newPlan.version() + " 不一致");
        }
        if (!oldPlan.opDate().equals(newPlan.opDate())) {
            throw conflict("OP_DATE_MISMATCH",
                    "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
        }
        if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
            throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
        }
        if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
            throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
        }
        List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
        List<Map<String, Object>> conflicts = new ArrayList<>();
        conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
        // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
        conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                List.of(newPlan.id(), oldPlan.id())));
        conflicts.addAll(findBlockConflicts(req.newScheduleKey(), newPlan.opDate(),
                occupancies, Set.of()));
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "新草稿存在时隙冲突，改签未生效", conflicts);
        }
        long now = System.currentTimeMillis();
        planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
        planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
        planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
        return new RescheduleResponse(loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
    }

    /**
     * 占用清单与已生效封锁窗口的相交检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findBlockConflicts(String scheduleKey, LocalDate opDate,
                                                         List<Occupancy> occupancies,
                                                         Set<Long> excludeBlockIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<WorkBlock> blocks = blockRepo.findEffectiveBlocks(opDate, sectionIds).stream()
                .filter(b -> !excludeBlockIds.contains(b.id()))
                .toList();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (WorkBlock block : blocks) {
                if (!o.sectionId().equals(block.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(block.endUtc())
                        && block.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "WORK_BLOCK_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("blockKey", block.blockKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 封锁登记中多个后继草稿相互之间的同区段相交检测。
     */
    private List<Map<String, Object>> findMutualOccupancyConflicts(
            Map<String, DayPlan> successorDrafts, List<Occupancy> successorOccupancies) {
        Map<Long, String> planKeyById = successorDrafts.values().stream()
                .collect(java.util.stream.Collectors.toMap(DayPlan::id, DayPlan::scheduleKey));
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < successorOccupancies.size(); i++) {
            Occupancy a = successorOccupancies.get(i);
            for (int j = i + 1; j < successorOccupancies.size(); j++) {
                Occupancy b = successorOccupancies.get(j);
                if (a.planId() == b.planId() || !a.sectionId().equals(b.sectionId())) {
                    continue;
                }
                boolean overlap = a.startUtc().isBefore(b.endUtc())
                        && b.startUtc().isBefore(a.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", a.sectionId());
                    detail.put("scheduleKey", planKeyById.get(a.planId()));
                    detail.put("conflictingScheduleKey", planKeyById.get(b.planId()));
                    detail.put("trainNo", a.trainNo());
                    detail.put("startUtc", a.startUtc().toString());
                    detail.put("endUtc", a.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    private WorkBlockResponse toWorkBlockResponse(WorkBlock block) {
        List<AffectedPlanRecord> records = blockRepo.findAffectedPlans(block.id());
        Map<Long, List<Occupancy>> slotsByAffectedId =
                blockRepo.findSlotsByAffectedPlanIds(records.stream().map(AffectedPlanRecord::id).toList());
        List<AffectedPlanView> views = records.stream().map(r -> {
            String successorKey = null;
            if (r.successorPlanId() != null) {
                successorKey = planRepo.findById(r.successorPlanId())
                        .map(DayPlan::scheduleKey).orElse(null);
            }
            List<OccupancyView> slots = slotsByAffectedId.getOrDefault(r.id(), List.of()).stream()
                    .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                    .toList();
            return new AffectedPlanView(r.scheduleKey(), r.disposition().name(), successorKey, slots);
        }).toList();
        return new WorkBlockResponse(block.blockKey(), block.sectionId(), block.opDate(),
                block.startUtc(), block.endUtc(), block.status().name(), views);
    }

    private void validateBlockWindow(Instant startUtc, Instant endUtc) {
        if (!endUtc.isAfter(startUtc)) {
            throw badRequest("封锁结束时刻必须晚于开始时刻");
        }
    }

    private void validateWorkBlockActions(List<WorkBlockPlanAction> plans) {
        Set<String> seen = new HashSet<>();
        Set<String> successorKeys = new HashSet<>();
        for (WorkBlockPlanAction action : plans) {
            if (!"CANCEL".equals(action.action()) && !"RESCHEDULE".equals(action.action())) {
                throw badRequest("不支持的封锁处理动作: " + action.action());
            }
            if ("CANCEL".equals(action.action())
                    && action.newScheduleKey() != null && !action.newScheduleKey().isBlank()) {
                throw badRequest("CANCEL 动作不得携带 newScheduleKey: " + action.scheduleKey());
            }
            if (!seen.add(action.scheduleKey())) {
                throw badRequest("封锁处理清单中计划重复: " + action.scheduleKey());
            }
            if ("RESCHEDULE".equals(action.action()) && action.newScheduleKey() != null
                    && !action.newScheduleKey().isBlank()
                    && !successorKeys.add(action.newScheduleKey())) {
                throw badRequest("同一后继草稿在封锁处理清单中重复出现: "
                        + action.newScheduleKey());
            }
        }
    }

    /**
     * 占用式窗口必须完全落在单个运营日（Asia/Shanghai）[dayStart, dayEnd) 内。
     */
    private void validateWindowWithinOperationDay(Instant startUtc, Instant endUtc, LocalDate opDate) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        if (startUtc.isBefore(dayStart) || endUtc.isAfter(dayEnd)) {
            throw badRequest("封锁窗口不在运营日 " + opDate + "（Asia/Shanghai）内");
        }
    }

    private ApiException badRequestPlan(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private String hashWorkBlock(RegisterWorkBlockRequest req) {
        StringBuilder sb = new StringBuilder(OP_WORK_BLOCK).append('\n')
                .append(req.blockKey()).append('\n')
                .append(req.sectionId()).append('\n')
                .append(req.startUtc().toEpochMilli()).append('\n')
                .append(req.endUtc().toEpochMilli());
        for (WorkBlockPlanAction action : req.plans()) {
            sb.append('\n').append(action.scheduleKey()).append('|').append(action.action())
                    .append('|').append(action.newScheduleKey() == null ? "" : action.newScheduleKey());
        }
        return sha256(sb.toString());
    }

    private String hashBatchReschedule(BatchRescheduleRequest req) {
        StringBuilder sb = new StringBuilder(OP_BATCH_RESCHEDULE);
        for (BatchRescheduleItem item : req.plans()) {
            sb.append('\n').append(item.oldScheduleKey()).append('|')
                    .append(item.newScheduleKey()).append('|')
                    .append(item.expectedOldVersion()).append('|')
                    .append(item.expectedNewVersion());
        }
        return sha256(sb.toString());
    }


    /**
     * 查询包含指定计划在内的完整有序改签链（从最前驱到最后继）；
     * 无改签历史的计划链中仅含自身。计划不存在返回 404。
     */
    public RescheduleChainResponse getRescheduleChain(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<DayPlan> predecessors = new ArrayList<>();
        DayPlan cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkBySuccessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().predecessorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链前驱缺失: " + link.get()));
            predecessors.add(cursor);
        }
        Collections.reverse(predecessors);
        List<DayPlan> chain = new ArrayList<>(predecessors);
        chain.add(plan);
        cursor = plan;
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkByPredecessor(cursor.id());
            if (link.isEmpty()) {
                break;
            }
            cursor = planRepo.findById(link.get().successorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链后继缺失: " + link.get()));
            chain.add(cursor);
        }
        List<RescheduleChainItem> items = chain.stream()
                .map(p -> new RescheduleChainItem(p.scheduleKey(), p.opDate(), p.version(),
                        p.status().name()))
                .toList();
        return new RescheduleChainResponse(scheduleKey, items);
    }

    /**
     * 按计划业务键查询明细（含历史占用），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    /**
     * 参数级校验：结束必须晚于开始，且起止落在同一 Asia/Shanghai 日历日内。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies) {
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (!o.endUtc().isAfter(o.startUtc())) {
                throw badRequest("第 " + i + " 条占用结束时刻必须晚于开始时刻");
            }
            LocalDate startDay = o.startUtc().atZone(OPERATION_ZONE).toLocalDate();
            LocalDate endDay = o.endUtc().minusNanos(1).atZone(OPERATION_ZONE).toLocalDate();
            if (!startDay.equals(endDay)) {
                throw badRequest("第 " + i + " 条占用必须落在同一运营日（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 占用必须落在计划运营日内：[start, end) 完全包含于运营日 [dayStart, dayEnd)。
     */
    private void validateWithinOperationDay(List<OccupancyRequest> occupancies, LocalDate opDate) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                throw badRequest("第 " + i + " 条占用不在运营日 " + opDate + "（Asia/Shanghai）内");
            }
        }
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(String scheduleKey, List<Occupancy> occupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Occupancy>> byTrain = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            byTrain.computeIfAbsent(o.trainNo(), k -> new ArrayList<>()).add(o);
        }
        byTrain.forEach((trainNo, list) -> {
            List<Occupancy> sorted = list.stream()
                    .sorted(Comparator.comparing(Occupancy::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Occupancy prev = sorted.get(i - 1);
                Occupancy cur = sorted.get(i);
                if (cur.startUtc().isBefore(prev.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "TRAIN_OVERLAP");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("trainNo", trainNo);
                    detail.put("sectionId", cur.sectionId());
                    detail.put("startUtc", cur.startUtc().toString());
                    detail.put("endUtc", cur.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        });
        return conflicts;
    }

    /**
     * 与其他已发布计划在同日期、同区段上的重叠检测（左闭右开，相邻合法）。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    private List<Map<String, Object>> findSectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds,
                excludePlanIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<PlanResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash, PlanResponse.class);
    }

    /**
     * 幂等重放（泛型）：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
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

    /**
     * 并发下唯一键冲突后的裁决：若为同键重放返回首次结果，否则说明 scheduleKey 冲突。
     */
    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    private String hashCreate(CreatePlanRequest req) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        StringBuilder sb = new StringBuilder(OP_UPDATE).append('\n')
                .append(scheduleKey).append('\n').append(req.expectedVersion());
        appendOccupancies(sb, req.occupancies());
        return sha256(sb.toString());
    }

    private String hashAction(String opType, String scheduleKey) {
        return sha256(opType + '\n' + scheduleKey);
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        return sha256(OP_RESCHEDULE + '\n' + oldScheduleKey + '\n' + req.newScheduleKey()
                + '\n' + req.expectedOldVersion() + '\n' + req.expectedNewVersion());
    }

    private void appendOccupancies(StringBuilder sb, List<OccupancyRequest> occupancies) {
        for (OccupancyRequest o : occupancies) {
            sb.append('\n').append(o.trainNo()).append('|').append(o.sectionId()).append('|')
                    .append(o.startUtc().toEpochMilli()).append('|').append(o.endUtc().toEpochMilli());
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
