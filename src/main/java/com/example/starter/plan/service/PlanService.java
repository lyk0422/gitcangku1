package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.HazardConfigVersion;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.HazardConfigRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItemRequest;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardCarRequest;
import com.example.starter.plan.web.dto.HazardCarView;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishHistoryResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签、批量改签与查询。
 *
 * <p>危险品隔离：计划可按列车登记危险品车厢（位置从 1 开始）；发布与改签均在完整编组与
 * 完整区段路径上校验车厢位置重复、互斥类别最小间隔、限制区段开放清单授权与配置有效性，
 * 任一冲突返回 422 并列出冲突对。发布成功固化不可变快照（编组、类别、配置版本与校验结论），
 * 取消计划、改签或后续配置更新均不改写历史。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布、改签、批量改签与配置写入经全局发布锁串行化，同一计划的
 * 更新/发布/取消/改签经行锁按事务提交顺序生效；仅成功结果写入幂等记录，失败不缓存、可重试。
 * 指纹由操作者、操作类型、规范化集合及全部影响结果字段经规范化 JSON 取 SHA-256 得到。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 操作者缺省值。 */
    public static final String DEFAULT_OPERATOR = "system";

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";

    private final PlanRepository planRepo;
    private final HazardConfigRepository configRepo;
    private final IdempotencyRepository idemRepo;
    private final HazardValidator hazardValidator;
    private final CanonicalHasher hasher;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final TimeProvider timeProvider;

    public PlanService(PlanRepository planRepo, HazardConfigRepository configRepo,
                       IdempotencyRepository idemRepo, HazardValidator hazardValidator,
                       CanonicalHasher hasher, ObjectMapper objectMapper,
                       PlatformTransactionManager txManager, TimeProvider timeProvider) {
        this.planRepo = planRepo;
        this.configRepo = configRepo;
        this.idemRepo = idemRepo;
        this.hazardValidator = hazardValidator;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.timeProvider = timeProvider;
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT），危险品编组随占用一并登记。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        String operator = normalizeOperator(req.operator());
        validateOccupancyParams(req.occupancies());
        validateHazardCarParams(req.hazardCars());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String hash = hashCreate(operator, req);
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                long now = timeProvider.nowMillis();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT, now);
                planRepo.insertOccupancies(planId, toOccupancies(planId, req.occupancies()));
                planRepo.insertHazardCars(planId, toHazardCars(planId, req.hazardCars()));
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CREATE, req.requestKey(), hash);
        }
    }

    /**
     * 整体替换草稿占用清单与危险品编组，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
        String operator = normalizeOperator(req.operator());
        validateOccupancyParams(req.occupancies());
        validateHazardCarParams(req.hazardCars());
        String hash = hashUpdate(operator, scheduleKey, req);
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
                long now = timeProvider.nowMillis();
                planRepo.replaceOccupancies(plan.id(), toOccupancies(plan.id(), req.occupancies()));
                planRepo.replaceHazardCars(plan.id(), toHazardCars(plan.id(), req.hazardCars()));
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
     * 发布计划（操作者缺省 system），便于无操作者场景与既有调用方使用。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        return publish(scheduleKey, requestKey, null);
    }

    /**
     * 发布计划：全局发布锁内原子校验时隙冲突与危险品隔离（完整编组、完整区段路径），
     * 任一冲突则整张计划保持草稿并抛出 422（携带全部冲突对）；成功后固化不可变发布快照。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operatorRaw) {
        String operator = normalizeOperator(operatorRaw);
        String hash = hashAction(OP_PUBLISH, operator, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 并发同参请求可能在等待锁期间已由先提交事务完成，持锁后复查幂等记录
                Optional<PlanResponse> lockedReplay = replayIfPresent(OP_PUBLISH, requestKey, hash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                long now = timeProvider.nowMillis();
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<HazardCar> cars = planRepo.findHazardCars(plan.id());
                HazardValidator.EffectiveConfig effective = loadEffectiveConfig(now);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
                conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
                conflicts.addAll(hazardValidator.validate(occupancies, cars, effective));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "存在时隙或危险品隔离冲突，计划保持草稿", conflicts);
                }
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                insertPublishSnapshot(plan, cars, effective, operator, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划（操作者缺省 system）。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        return cancel(scheduleKey, requestKey, null);
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划、占用、编组与发布快照保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey, String operatorRaw) {
        String operator = normalizeOperator(operatorRaw);
        String hash = hashAction(OP_CANCEL, operator, scheduleKey);
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
                long now = timeProvider.nowMillis();
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
     * 原子改签：全局发布锁内在新草稿的完整编组与完整区段路径上校验时隙与危险品隔离，
     * 通过后同一事务取消旧计划、发布新计划、固化新计划快照并追加不可变前后继关联。
     * 任一步失败整体回滚：旧计划仍发布、新计划仍草稿，版本、占用、编组、快照与关联均不改变。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        String operator = normalizeOperator(req.operator());
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String hash = hashReschedule(operator, oldScheduleKey, req);
        Optional<RescheduleResponse> replay =
                replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 并发同参请求可能在等待锁期间已由先提交事务完成，持锁后复查幂等记录
                Optional<RescheduleResponse> lockedReplay =
                        replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash,
                                RescheduleResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                verifyRescheduleState(oldScheduleKey, req, oldPlan, newPlan);
                long now = timeProvider.nowMillis();
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<HazardCar> cars = planRepo.findHazardCars(newPlan.id());
                HazardValidator.EffectiveConfig effective = loadEffectiveConfig(now);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(req.newScheduleKey(), occupancies));
                // 仅排除旧计划与自身占用，第三方已发布计划照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(newPlan, occupancies,
                        List.of(newPlan.id(), oldPlan.id())));
                conflicts.addAll(hazardValidator.validate(occupancies, cars, effective));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙或危险品隔离冲突，改签未生效", conflicts);
                }
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
                insertPublishSnapshot(newPlan, cars, effective, operator, now);
                RescheduleResponse response = new RescheduleResponse(
                        loadPlan(oldScheduleKey), loadPlan(req.newScheduleKey()));
                idemRepo.insert(OP_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "改签前后继关联冲突"));
        }
    }

    /**
     * 批量改签：先验证整批完整后态（状态/版本/关联、批内键不重复、时隙冲突、
     * 批内新计划互相冲突、危险品隔离），全部合法后在一个事务内提交；
     * 任一计划非法则整次 422/409，时隙与编组均不改变，不留部分结果。
     */
    public BatchRescheduleResponse batchReschedule(BatchRescheduleRequest req) {
        String operator = normalizeOperator(req.operator());
        String hash = hashBatchReschedule(operator, req);
        Optional<BatchRescheduleResponse> replay =
                replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                        BatchRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 并发同参请求可能在等待锁期间已由先提交事务完成，持锁后复查幂等记录
                Optional<BatchRescheduleResponse> lockedReplay =
                        replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                                BatchRescheduleResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                List<BatchItemContext> contexts = new ArrayList<>();
                Set<String> oldKeys = new HashSet<>();
                Set<String> newKeys = new HashSet<>();
                Set<Long> excludePlanIds = new HashSet<>();
                for (BatchRescheduleItemRequest item : req.items()) {
                    if (item.oldScheduleKey().equals(item.newScheduleKey())) {
                        throw badRequest("新旧计划必须不同: " + item.oldScheduleKey());
                    }
                    if (!oldKeys.add(item.oldScheduleKey()) || !newKeys.add(item.newScheduleKey())
                            || oldKeys.contains(item.newScheduleKey())
                            || newKeys.contains(item.oldScheduleKey())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                                "批量改签中计划键重复使用",
                                List.of(batchDetail(item, "BATCH_KEY_DUPLICATED",
                                        "同一计划在批量改签中不得重复担任旧/新计划")));
                    }
                    DayPlan oldPlan = planRepo.findByKeyForUpdate(item.oldScheduleKey())
                            .orElseThrow(() -> notFound(item.oldScheduleKey()));
                    DayPlan newPlan = planRepo.findByKeyForUpdate(item.newScheduleKey())
                            .orElseThrow(() -> notFound(item.newScheduleKey()));
                    verifyRescheduleState(item.oldScheduleKey(),
                            new RescheduleRequest(req.requestKey(), operator,
                                    item.newScheduleKey(), item.expectedOldVersion(),
                                    item.expectedNewVersion()),
                            oldPlan, newPlan);
                    contexts.add(new BatchItemContext(item, oldPlan, newPlan));
                    excludePlanIds.add(oldPlan.id());
                    excludePlanIds.add(newPlan.id());
                }
                long now = timeProvider.nowMillis();
                HazardValidator.EffectiveConfig effective = loadEffectiveConfig(now);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                List<BatchLoaded> loaded = new ArrayList<>();
                for (BatchItemContext ctx : contexts) {
                    List<Occupancy> occupancies = planRepo.findOccupancies(ctx.newPlan.id());
                    List<HazardCar> cars = planRepo.findHazardCars(ctx.newPlan.id());
                    conflicts.addAll(findTrainOverlaps(ctx.item.newScheduleKey(), occupancies));
                    conflicts.addAll(findSectionConflicts(ctx.newPlan, occupancies,
                            new ArrayList<>(excludePlanIds)));
                    conflicts.addAll(hazardValidator.validate(occupancies, cars, effective));
                    loaded.add(new BatchLoaded(ctx, occupancies, cars));
                }
                // 批内新计划之间在同区段、同时段上不得互相冲突（完整后态裁决）
                conflicts.addAll(findWithinBatchSectionConflicts(loaded));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "批量改签含非法计划，整批未生效", conflicts);
                }
                List<RescheduleResponse> responses = new ArrayList<>();
                for (BatchLoaded l : loaded) {
                    planRepo.updateStatus(l.ctx.oldPlan.id(), PlanStatus.CANCELLED, now);
                    planRepo.updateStatus(l.ctx.newPlan.id(), PlanStatus.PUBLISHED, now);
                    planRepo.insertRescheduleLink(l.ctx.oldPlan.id(), l.ctx.newPlan.id(), now);
                    insertPublishSnapshot(l.ctx.newPlan, l.cars, effective, operator, now);
                }
                for (BatchLoaded l : loaded) {
                    responses.add(new RescheduleResponse(
                            loadPlan(l.ctx.item.oldScheduleKey()),
                            loadPlan(l.ctx.item.newScheduleKey())));
                }
                BatchRescheduleResponse response = new BatchRescheduleResponse(responses);
                idemRepo.insert(OP_BATCH_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                    BatchRescheduleResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "批量改签前后继关联冲突"));
        }
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
     * 按计划业务键查询明细（含历史占用与危险品编组），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙（只读，不修改状态）。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(s -> new PublishedSlotView(s.scheduleKey(), s.trainNo(), s.sectionId(),
                        s.startUtc(), s.endUtc()))
                .toList();
    }

    /**
     * 查询计划的发布快照（固化编组、配置版本与校验结论）；计划不存在 404，未发布 404。
     */
    public PublishSnapshotView getPublishSnapshot(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        PublishSnapshot snapshot = planRepo.findSnapshotByPlan(plan.id())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SNAPSHOT_NOT_FOUND",
                        "计划尚无发布快照: " + scheduleKey));
        return toSnapshotView(snapshot);
    }

    /**
     * 查询发布历史（按发布时刻升序、快照 id 升序），可按运营日闭区间过滤、限制条数；只读。
     */
    public PublishHistoryResponse getPublishHistory(LocalDate fromOpDate, LocalDate toOpDate,
                                                    Integer limit) {
        List<PublishSnapshotView> views = planRepo.findSnapshots(fromOpDate, toOpDate, limit)
                .stream().map(this::toSnapshotView).toList();
        return new PublishHistoryResponse(views);
    }

    /**
     * 只读诊断：对计划当前完整编组与完整区段路径执行与发布完全一致的校验
     * （列车内部重叠、跨计划区段冲突、危险品隔离与配置有效性），返回全部冲突明细。
     * 不发布、不写快照、不修改任何状态；非草稿计划也可诊断其当前数据。
     */
    public com.example.starter.plan.web.dto.HazardDiagnosisResponse diagnose(String scheduleKey) {
        return readOnlyTx.execute(status -> {
            DayPlan plan = planRepo.findByKey(scheduleKey)
                    .orElseThrow(() -> notFound(scheduleKey));
            long now = timeProvider.nowMillis();
            List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
            List<HazardCar> cars = planRepo.findHazardCars(plan.id());
            HazardValidator.EffectiveConfig effective = loadEffectiveConfig(now);
            List<Map<String, Object>> conflicts = new ArrayList<>();
            conflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
            conflicts.addAll(findSectionConflicts(plan, occupancies, List.of(plan.id())));
            conflicts.addAll(hazardValidator.validate(occupancies, cars, effective));
            Integer configVersionNo = effective == null ? null
                    : effective.version().versionNo();
            return new com.example.starter.plan.web.dto.HazardDiagnosisResponse(
                    conflicts.isEmpty(), configVersionNo, conflicts);
        });
    }

    // ---------- 内部实现 ----------

    private PublishSnapshotView toSnapshotView(PublishSnapshot snapshot) {
        List<HazardCarView> cars = snapshot.cars().stream()
                .map(c -> new HazardCarView(c.trainNo(), c.position(), c.hazardClass()))
                .toList();
        List<PublishSnapshotView.SnapshotSectionView> sections = snapshot.openSections().stream()
                .map(s -> new PublishSnapshotView.SnapshotSectionView(s.sectionId(),
                        s.hazardClass()))
                .toList();
        return new PublishSnapshotView(snapshot.id(), snapshot.configVersionId(),
                snapshot.configVersionNo(), snapshot.configFingerprint(), snapshot.conclusion(),
                snapshot.operator(), Instant.ofEpochMilli(snapshot.createdAt()), cars, sections);
    }

    private void insertPublishSnapshot(DayPlan plan, List<HazardCar> cars,
                                       HazardValidator.EffectiveConfig effective,
                                       String operator, long nowMillis) {
        Long configId = null;
        Integer configVersionNo = null;
        String configFingerprint = null;
        List<PublishSnapshot.SnapshotOpenSection> sectionCopies = List.of();
        if (effective != null) {
            configId = effective.version().id();
            configVersionNo = effective.version().versionNo();
            configFingerprint = effective.version().fingerprint();
            List<PublishSnapshot.SnapshotOpenSection> copies = new ArrayList<>();
            effective.openBySection().forEach((sectionId, classes) ->
                    classes.forEach(hazardClass ->
                            copies.add(new PublishSnapshot.SnapshotOpenSection(sectionId,
                                    hazardClass))));
            sectionCopies = copies;
        }
        long snapshotId = planRepo.insertPublishSnapshot(plan.id(), configId, configVersionNo,
                configFingerprint, "PASS", operator, nowMillis);
        planRepo.insertSnapshotCars(snapshotId, cars);
        planRepo.insertSnapshotSections(snapshotId, sectionCopies);
    }

    /**
     * 加载指定 UTC 时刻生效的危险品配置；无生效版本返回 null（语义：无任何限制）。
     */
    private HazardValidator.EffectiveConfig loadEffectiveConfig(long nowMillis) {
        Optional<HazardConfigVersion> version =
                configRepo.findEffectiveAt(Instant.ofEpochMilli(nowMillis));
        if (version.isEmpty()) {
            return null;
        }
        HazardConfigVersion v = version.get();
        TreeMap<String, Map<String, Integer>> separations = new TreeMap<>();
        configRepo.findSeparations(v.id()).forEach(r ->
                separations.computeIfAbsent(r.classA(), k -> new TreeMap<>())
                        .put(r.classB(), r.minCars()));
        SortedSet<String> restricted = configRepo.findRestrictedSections(v.id());
        TreeMap<String, SortedSet<String>> openBySection = configRepo.findOpenClassMap(v.id());
        restricted.forEach(s -> openBySection.putIfAbsent(s, new TreeSet<>()));
        return new HazardValidator.EffectiveConfig(v, separations, restricted, openBySection);
    }

    private void verifyRescheduleState(String oldScheduleKey, RescheduleRequest req,
                                       DayPlan oldPlan, DayPlan newPlan) {
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
    }

    /**
     * 批内新计划两两区段重叠检测（左闭右开），冲突对双方都列出，scheduleKey 字典序在前的为 a。
     */
    private List<Map<String, Object>> findWithinBatchSectionConflicts(List<BatchLoaded> loaded) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < loaded.size(); i++) {
            for (int j = i + 1; j < loaded.size(); j++) {
                BatchLoaded a = loaded.get(i);
                BatchLoaded b = loaded.get(j);
                for (Occupancy oa : a.occupancies) {
                    for (Occupancy ob : b.occupancies) {
                        if (!oa.sectionId().equals(ob.sectionId())) {
                            continue;
                        }
                        boolean overlap = oa.startUtc().isBefore(ob.endUtc())
                                && ob.startUtc().isBefore(oa.endUtc());
                        if (overlap) {
                            String keyA = a.ctx.item.newScheduleKey();
                            String keyB = b.ctx.item.newScheduleKey();
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", "BATCH_SECTION_CONFLICT");
                            detail.put("sectionId", oa.sectionId());
                            detail.put("scheduleKeyA", keyA);
                            detail.put("scheduleKeyB", keyB);
                            detail.put("trainNoA", oa.trainNo());
                            detail.put("trainNoB", ob.trainNo());
                            detail.put("startUtcA", oa.startUtc().toString());
                            detail.put("endUtcA", oa.endUtc().toString());
                            detail.put("startUtcB", ob.startUtc().toString());
                            detail.put("endUtcB", ob.endUtc().toString());
                            conflicts.add(detail);
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    private Map<String, Object> batchDetail(BatchRescheduleItemRequest item, String type,
                                            String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("oldScheduleKey", item.oldScheduleKey());
        detail.put("newScheduleKey", item.newScheduleKey());
        detail.put("message", message);
        return detail;
    }

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        List<HazardCarView> cars = planRepo.findHazardCars(plan.id()).stream()
                .map(c -> new HazardCarView(c.trainNo(), c.position(), c.hazardClass()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views, cars);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    private List<HazardCar> toHazardCars(long planId, List<HazardCarRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        List<HazardCar> result = new ArrayList<>(requests.size());
        for (HazardCarRequest r : requests) {
            result.add(new HazardCar(0L, planId, r.trainNo(), r.position(), r.hazardClass()));
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
     * 危险品车厢登记参数校验：位置为正整数由 Bean Validation 保证，这里拒绝空白类别。
     * 位置重复、列车不在路径等业务冲突在发布/改签的完整编组校验时判 422。
     */
    private void validateHazardCarParams(List<HazardCarRequest> cars) {
        if (cars == null) {
            return;
        }
        for (int i = 0; i < cars.size(); i++) {
            HazardCarRequest c = cars.get(i);
            if (c.position() == null || c.position() < 1) {
                throw badRequest("第 " + i + " 节危险品车厢位置必须为正整数");
            }
            if (c.hazardClass() == null || c.hazardClass().isBlank()) {
                throw badRequest("第 " + i + " 节危险品车厢类别不能为空");
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
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划；批量为全部新旧计划）
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

    // ---------- 规范化指纹（操作者 + 操作类型 + 规范化集合 + 全部影响结果字段） ----------

    private String hashCreate(String operator, CreatePlanRequest req) {
        TreeMap<String, Object> root = hasher.object(
                "opType", OP_CREATE,
                "operator", operator,
                "scheduleKey", req.scheduleKey(),
                "opDate", req.opDate().toString(),
                "occupancies", occupanciesNode(req.occupancies()),
                "hazardCars", hazardCarsNode(req.hazardCars()));
        return hasher.sha256(root);
    }

    private String hashUpdate(String operator, String scheduleKey, UpdateOccupanciesRequest req) {
        TreeMap<String, Object> root = hasher.object(
                "opType", OP_UPDATE,
                "operator", operator,
                "scheduleKey", scheduleKey,
                "expectedVersion", req.expectedVersion(),
                "occupancies", occupanciesNode(req.occupancies()),
                "hazardCars", hazardCarsNode(req.hazardCars()));
        return hasher.sha256(root);
    }

    private String hashAction(String opType, String operator, String scheduleKey) {
        return hasher.sha256(hasher.object(
                "opType", opType,
                "operator", operator,
                "scheduleKey", scheduleKey));
    }

    private String hashReschedule(String operator, String oldScheduleKey, RescheduleRequest req) {
        return hasher.sha256(hasher.object(
                "opType", OP_RESCHEDULE,
                "operator", operator,
                "oldScheduleKey", oldScheduleKey,
                "newScheduleKey", req.newScheduleKey(),
                "expectedOldVersion", req.expectedOldVersion(),
                "expectedNewVersion", req.expectedNewVersion()));
    }

    private String hashBatchReschedule(String operator, BatchRescheduleRequest req) {
        List<TreeMap<String, Object>> items = new ArrayList<>();
        // 批量改签按请求顺序裁决；请求顺序本身是影响结果字段，须纳入指纹
        for (BatchRescheduleItemRequest item : req.items()) {
            items.add(hasher.object(
                    "oldScheduleKey", item.oldScheduleKey(),
                    "newScheduleKey", item.newScheduleKey(),
                    "expectedOldVersion", item.expectedOldVersion(),
                    "expectedNewVersion", item.expectedNewVersion()));
        }
        return hasher.sha256(hasher.object(
                "opType", OP_BATCH_RESCHEDULE,
                "operator", operator,
                "items", items));
    }

    private List<TreeMap<String, Object>> occupanciesNode(List<OccupancyRequest> occupancies) {
        List<TreeMap<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < occupancies.size(); i++) {
            OccupancyRequest o = occupancies.get(i);
            nodes.add(hasher.object(
                    "seq", i,
                    "trainNo", o.trainNo(),
                    "sectionId", o.sectionId(),
                    "startUtc", o.startUtc().toEpochMilli(),
                    "endUtc", o.endUtc().toEpochMilli()));
        }
        return nodes;
    }

    private List<TreeMap<String, Object>> hazardCarsNode(List<HazardCarRequest> cars) {
        if (cars == null || cars.isEmpty()) {
            return List.of();
        }
        List<HazardCarRequest> sorted = new ArrayList<>(cars);
        sorted.sort(Comparator.comparing(HazardCarRequest::trainNo)
                .thenComparing(HazardCarRequest::position)
                .thenComparing(HazardCarRequest::hazardClass));
        List<TreeMap<String, Object>> nodes = new ArrayList<>();
        for (HazardCarRequest c : sorted) {
            nodes.add(hasher.object(
                    "trainNo", c.trainNo(),
                    "position", c.position(),
                    "hazardClass", c.hazardClass()));
        }
        return nodes;
    }

    private String normalizeOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return DEFAULT_OPERATOR;
        }
        return operator;
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

    /**
     * 批量改签单项的已加锁计划上下文。
     */
    private record BatchItemContext(BatchRescheduleItemRequest item, DayPlan oldPlan,
                                    DayPlan newPlan) {
    }

    /**
     * 批量改签单项的完整后态数据。
     */
    private record BatchLoaded(BatchItemContext ctx, List<Occupancy> occupancies,
                               List<HazardCar> cars) {
    }
}
