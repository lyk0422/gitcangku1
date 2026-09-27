package com.example.starter.plan.service;

import com.example.starter.plan.model.ConsistCar;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.HazardRuleSet;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.SectionRestriction;
import com.example.starter.plan.repo.ConsistRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PublishSnapshotRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItemRequest;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.ConsistCarRequest;
import com.example.starter.plan.web.dto.ConsistCarView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanDiagnosticsView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.SnapshotSectionView;
import com.example.starter.plan.web.dto.UpdateConsistRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、危险品编组登记、发布（含危险品隔离校验与快照）、
 * 取消、单个/批量原子改签与查询诊断。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次完整响应，
 * 同键不同参返回 409；发布、改签与危险品配置写入经全局发布锁串行化，同一计划的更新/编组/
 * 发布/取消/改签经行锁按事务提交顺序生效；同计划并发发布最多一次成功；仅成功结果写入幂等记录，
 * 失败（含 422）不缓存、可修正后重试。指纹由操作者、操作类型、规范化集合及全部影响结果字段经
 * 规范化 JSON + SHA-256 构造，不使用未转义分隔符拼接。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 操作者缺省值。 */
    public static final String DEFAULT_OPERATOR = "system";

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_CONSIST = "CONSIST";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";

    private final PlanRepository planRepo;
    private final ConsistRepository consistRepo;
    private final HazardConfigService hazardConfigService;
    private final PublishSnapshotRepository snapshotRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, ConsistRepository consistRepo,
                       HazardConfigService hazardConfigService,
                       PublishSnapshotRepository snapshotRepo,
                       IdempotencyRepository idemRepo,
                       ObjectMapper objectMapper, Clock clock,
                       PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.consistRepo = consistRepo;
        this.hazardConfigService = hazardConfigService;
        this.snapshotRepo = snapshotRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT，无危险品编组）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String hash = fingerprint(fpCreate(req));
        Optional<PlanResponse> replay = replayIfPresent(OP_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                long now = clock.millis();
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
        String hash = fingerprint(fpUpdate(scheduleKey, req));
        Optional<PlanResponse> replay = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                Optional<PlanResponse> lockedReplay = replayIfPresent(OP_UPDATE, req.requestKey(), hash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改占用，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 " + plan.version() + " 不一致");
                }
                validateWithinOperationDay(req.occupancies(), plan.opDate());                long now = clock.millis();
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
     * 整体替换草稿危险品编组，版本加一；仅 DRAFT 可改，expectedVersion 必须匹配。
     * 车厢位置重复返回 422 并列出冲突位置对，不落任何变更。
     */
    public PlanResponse replaceConsist(String scheduleKey, UpdateConsistRequest req) {
        List<HazardValidation.Car> cars = req.cars().stream()
                .map(c -> new HazardValidation.Car(c.position(), c.hazardClass())).toList();
        List<Map<String, Object>> duplicates = HazardValidation.findDuplicatePositions(cars);
        if (!duplicates.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "HAZARD_CONFLICT",
                    "危险品编组位置重复", duplicates);
        }
        String hash = fingerprint(fpConsist(scheduleKey, req));
        Optional<PlanResponse> replay = replayIfPresent(OP_CONSIST, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                Optional<PlanResponse> lockedReplay = replayIfPresent(OP_CONSIST, req.requestKey(), hash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可修改危险品编组，当前状态: " + plan.status());
                }
                if (req.expectedVersion() != plan.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前版本 " + plan.version() + " 不一致");
                }
                long now = clock.millis();
                List<ConsistCar> entities = new ArrayList<>(req.cars().size());
                for (ConsistCarRequest c : req.cars()) {
                    entities.add(new ConsistCar(0L, plan.id(), c.position(), c.hazardClass()));
                }
                consistRepo.replaceCars(plan.id(), entities);
                planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1, PlanStatus.DRAFT, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_CONSIST, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_CONSIST, req.requestKey(), hash);
        }
    }

    /**
     * 发布计划（操作者缺省 system）：全局发布锁内原子校验时隙冲突与危险品隔离
     * （完整编组 + 完整区段路径），通过后固化发布快照；任一冲突则计划保持草稿并返回 422。
     */
    public PlanResponse publish(String scheduleKey, String requestKey) {
        return publish(scheduleKey, requestKey, null);
    }

    /**
     * 发布计划并记录操作者到发布快照。
     */
    public PlanResponse publish(String scheduleKey, String requestKey, String operator) {
        String op = normalizeOperator(operator);
        String hash = fingerprint(fpAction(OP_PUBLISH, scheduleKey, op));
        Optional<PlanResponse> replay = replayIfPresent(OP_PUBLISH, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                Optional<PlanResponse> lockedReplay = replayIfPresent(OP_PUBLISH, requestKey, hash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                if (plan.status() != PlanStatus.DRAFT) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅草稿可发布，当前状态: " + plan.status());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<ConsistCar> consist = consistRepo.findCars(plan.id());
                HazardContext hctx = loadHazardContext();
                ValidationOutcome outcome = validateForPublish(plan.scheduleKey(),
                        plan.opDate(), occupancies, consist, hctx, List.of(plan.id()));
                if (!outcome.slotConflicts().isEmpty() || !outcome.hazardConflicts().isEmpty()) {
                    throwConflict(outcome);
                }
                long now = clock.millis();
                planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                insertSnapshot(plan, consist, hctx, outcome.pathSections(),
                        outcome.restrictions(), op, now);
                PlanResponse response = loadPlan(scheduleKey);
                idemRepo.insert(OP_PUBLISH, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(OP_PUBLISH, requestKey, hash);
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划、占用、编组与发布快照保留不改写。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        String hash = fingerprint(fpAction(OP_CANCEL, scheduleKey, DEFAULT_OPERATOR));
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                Optional<PlanResponse> lockedReplay = replayIfPresent(OP_CANCEL, requestKey, hash);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可取消，当前状态: " + plan.status());
                }
                long now = clock.millis();
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
     * 原子改签：全局发布锁内对新草稿在完整编组与完整区段路径上校验时隙冲突与危险品隔离，
     * 通过后同一事务取消旧计划、发布新计划、追加不可变前后继关联、固化新计划发布快照。
     * 任一步失败整体回滚。
     */
    public RescheduleResponse reschedule(String oldScheduleKey, RescheduleRequest req) {
        if (oldScheduleKey.equals(req.newScheduleKey())) {
            throw badRequest("新旧计划必须不同: " + oldScheduleKey);
        }
        String op = normalizeOperator(req.operator());
        String hash = fingerprint(fpReschedule(oldScheduleKey, req, op));
        Optional<RescheduleResponse> replay =
                replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<RescheduleResponse> lockedReplay =
                        replayIfPresent(OP_RESCHEDULE, req.requestKey(), hash, RescheduleResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                DayPlan oldPlan = planRepo.findByKeyForUpdate(oldScheduleKey)
                        .orElseThrow(() -> notFound(oldScheduleKey));
                DayPlan newPlan = planRepo.findByKeyForUpdate(req.newScheduleKey())
                        .orElseThrow(() -> notFound(req.newScheduleKey()));
                assertRescheduleable(oldPlan, newPlan, req, oldScheduleKey, req.newScheduleKey());
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<ConsistCar> consist = consistRepo.findCars(newPlan.id());
                HazardContext hctx = loadHazardContext();
                ValidationOutcome outcome = validateForPublish(req.newScheduleKey(),
                        newPlan.opDate(), occupancies, consist, hctx,
                        List.of(newPlan.id(), oldPlan.id()));
                if (!outcome.slotConflicts().isEmpty() || !outcome.hazardConflicts().isEmpty()) {
                    throwConflict(outcome);
                }
                long now = clock.millis();
                commitReschedule(oldPlan, newPlan, consist, hctx, outcome, op, now);
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
     * 批量原子改签：先在完整后态上统一校验全部改签项（状态、版本、关联、时隙冲突含新计划互查、
     * 危险品隔离），全部合法才在一个事务内提交；任一项非法整次不改变任何时隙或编组。
     */
    public BatchRescheduleResponse batchReschedule(BatchRescheduleRequest req) {
        String op = normalizeOperator(req.operator());
        String hash = fingerprint(fpBatch(req, op));
        Optional<BatchRescheduleResponse> replay =
                replayIfPresent(OP_BATCH_RESCHEDULE, req.requestKey(), hash,
                        BatchRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<BatchRescheduleResponse> lockedReplay = replayIfPresent(
                        OP_BATCH_RESCHEDULE, req.requestKey(), hash, BatchRescheduleResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                List<BatchRescheduleItemRequest> items = req.items();
                assertBatchShape(items);

                // 按 id 排序加锁，避免死锁
                List<DayPlan> olds = new ArrayList<>();
                List<DayPlan> news = new ArrayList<>();
                for (BatchRescheduleItemRequest item : items) {
                    DayPlan oldPlan = planRepo.findByKeyForUpdate(item.oldScheduleKey())
                            .orElseThrow(() -> notFound(item.oldScheduleKey()));
                    DayPlan newPlan = planRepo.findByKeyForUpdate(item.newScheduleKey())
                            .orElseThrow(() -> notFound(item.newScheduleKey()));
                    olds.add(oldPlan);
                    news.add(newPlan);
                }
                for (int i = 0; i < items.size(); i++) {
                    assertRescheduleable(olds.get(i), news.get(i), items.get(i),
                            items.get(i).oldScheduleKey(), items.get(i).newScheduleKey());
                }

                HazardContext hctx = loadHazardContext();
                List<Long> excludeIds = new ArrayList<>();
                olds.forEach(p -> excludeIds.add(p.id()));
                news.forEach(p -> excludeIds.add(p.id()));

                List<List<Occupancy>> newOccupancies = new ArrayList<>();
                List<List<ConsistCar>> newConsists = new ArrayList<>();
                List<ValidationOutcome> outcomes = new ArrayList<>();
                List<Map<String, Object>> slotConflicts = new ArrayList<>();
                List<Map<String, Object>> hazardConflicts = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    List<Occupancy> occupancies = planRepo.findOccupancies(news.get(i).id());
                    List<ConsistCar> consist = consistRepo.findCars(news.get(i).id());
                    newOccupancies.add(occupancies);
                    newConsists.add(consist);
                    ValidationOutcome outcome = validateForPublish(items.get(i).newScheduleKey(),
                            news.get(i).opDate(), occupancies, consist, hctx, excludeIds);
                    outcomes.add(outcome);
                    slotConflicts.addAll(withItem(outcome.slotConflicts(), i, items.get(i)));
                    hazardConflicts.addAll(withItem(outcome.hazardConflicts(), i, items.get(i)));
                }
                // 完整后态：新计划之间在同区段重叠也判冲突
                for (int i = 0; i < items.size(); i++) {
                    for (int j = i + 1; j < items.size(); j++) {
                        slotConflicts.addAll(withItem(
                                findCrossPlanOverlaps(items.get(i).newScheduleKey(),
                                        newOccupancies.get(i), items.get(j).newScheduleKey(),
                                        newOccupancies.get(j)), i, items.get(i)));
                    }
                }
                if (!slotConflicts.isEmpty() || !hazardConflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            hazardConflicts.isEmpty() ? "SLOT_CONFLICT" : "HAZARD_CONFLICT",
                            "批量改签存在非法计划，整次未生效",
                                    union(slotConflicts, hazardConflicts));
                }

                long now = clock.millis();
                List<RescheduleResponse> results = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    commitReschedule(olds.get(i), news.get(i), newConsists.get(i), hctx,
                            outcomes.get(i), op, now);
                }
                for (BatchRescheduleItemRequest item : items) {
                    results.add(new RescheduleResponse(loadPlan(item.oldScheduleKey()),
                            loadPlan(item.newScheduleKey())));
                }
                BatchRescheduleResponse response = new BatchRescheduleResponse(items.size(), results);
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
     * 查询计划发布快照；计划不存在 404，计划从未发布 409。只读，不改状态。
     */
    public PublishSnapshotView getSnapshot(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        PublishSnapshotRepository.SnapshotRow row = snapshotRepo.findByPlanId(plan.id())
                .orElseThrow(() -> conflict("NO_PUBLISH_SNAPSHOT",
                        "计划尚无发布快照: " + scheduleKey));
        return new PublishSnapshotView(row.scheduleKey(), row.planVersion(),
                readConsist(row.consistJson()), row.ruleVersion(), row.rulesHash(),
                readStringList(row.pathSectionsJson()), readSnapshotSections(row.sectionRestrictionsJson()),
                row.result(), row.operator(), Instant.ofEpochMilli(row.createdAt()));
    }

    /**
     * 只读诊断：以当前危险品规则集与限制区段配置，对计划当前完整编组与完整区段路径执行与发布
     * 相同的校验，返回结论与全部冲突对；不修改任何状态。
     */
    public PlanDiagnosticsView diagnose(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<ConsistCar> consist = consistRepo.findCars(plan.id());
        HazardContext hctx = loadHazardContext();
        ValidationOutcome outcome = validateForPublish(plan.scheduleKey(), plan.opDate(),
                occupancies, consist, hctx, List.of(plan.id()));
        List<Map<String, Object>> conflicts = union(outcome.slotConflicts(),
                outcome.hazardConflicts());
        List<SnapshotSectionView> restrictionViews = outcome.restrictions().stream()
                .map(r -> new SnapshotSectionView(r.sectionId(), r.version(), r.allowedClasses()))
                .toList();
        List<ConsistCarView> carViews = consist.stream()
                .map(c -> new ConsistCarView(c.position(), c.hazardClass())).toList();
        return new PlanDiagnosticsView(plan.scheduleKey(), plan.version(), plan.status().name(),
                carViews, hctx.ruleSet().version(), hctx.ruleSet().rulesHash(),
                outcome.pathSections(), restrictionViews, conflicts,
                conflicts.isEmpty() ? "PASS" : "FAIL");
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
     * 按计划业务键查询明细（含历史占用与编组），不存在返回 404。
     */
    public PlanResponse getPlan(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw notFound(scheduleKey);
        }
        return loadPlan(scheduleKey);
    }

    /**
     * 查询指定运营日与区段上当前已发布的生效时隙（只读）。
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
        List<ConsistCarView> consistViews = consistRepo.findCars(plan.id()).stream()
                .map(c -> new ConsistCarView(c.position(), c.hazardClass())).toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), views, consistViews);
    }

    private List<Occupancy> toOccupancies(long planId, List<OccupancyRequest> requests) {
        List<Occupancy> result = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            OccupancyRequest r = requests.get(i);
            result.add(new Occupancy(0L, planId, i, r.trainNo(), r.sectionId(), r.startUtc(), r.endUtc()));
        }
        return result;
    }

    private String normalizeOperator(String operator) {
        return operator == null || operator.isBlank() ? DEFAULT_OPERATOR : operator;
    }

    /**
     * 发布前危险品配置上下文（在全局发布锁内读取，保证与提交时配置一致）。
     */
    private HazardContext loadHazardContext() {
        HazardRuleSet ruleSet = hazardConfigService.loadCurrentRuleSet();
        return new HazardContext(ruleSet);
    }

    /**
     * 在完整编组与完整区段路径上执行时隙 + 危险品隔离校验。
     *
     * @param excludePlanIds 跨计划时隙检测排除的计划 id（发布为自身；改签为新旧/批量全部计划）
     */
    private ValidationOutcome validateForPublish(String scheduleKey, LocalDate opDate,
                                                 List<Occupancy> occupancies,
                                                 List<ConsistCar> consist, HazardContext hctx,
                                                 List<Long> excludePlanIds) {
        List<Map<String, Object>> slotConflicts = new ArrayList<>();
        slotConflicts.addAll(findTrainOverlaps(scheduleKey, occupancies));
        slotConflicts.addAll(findSectionConflicts(scheduleKey, opDate, occupancies, excludePlanIds));

        List<String> pathSections = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<SectionRestriction> restrictions = new ArrayList<>();
        Map<String, List<String>> allowedBySection = new TreeMap<>();
        for (String sectionId : pathSections) {
            hazardConfigService.loadCurrentSection(sectionId).ifPresent(r -> {
                restrictions.add(r);
                allowedBySection.put(r.sectionId(), r.allowedClasses());
            });
        }
        List<HazardValidation.Car> cars = consist.stream()
                .map(c -> new HazardValidation.Car(c.position(), c.hazardClass())).toList();
        List<Map<String, Object>> hazardConflicts = new ArrayList<>();
        hazardConflicts.addAll(HazardValidation.findSeparationConflicts(
                hctx.ruleSet().rules(), cars));
        hazardConflicts.addAll(HazardValidation.findUnauthorizedClasses(
                pathSections, cars, allowedBySection));
        return new ValidationOutcome(pathSections, restrictions, slotConflicts, hazardConflicts);
    }

    private void throwConflict(ValidationOutcome outcome) {
        List<Map<String, Object>> all = union(outcome.slotConflicts(), outcome.hazardConflicts());
        String code = outcome.hazardConflicts().isEmpty() ? "SLOT_CONFLICT" : "HAZARD_CONFLICT";
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                "校验未通过，计划保持草稿/改签未生效", all);
    }

    private void insertSnapshot(DayPlan plan, List<ConsistCar> consist, HazardContext hctx,
                                List<String> pathSections, List<SectionRestriction> restrictions,
                                String operator, long nowMillis) {
        snapshotRepo.insert(plan.id(), plan.scheduleKey(), plan.version(),
                toJson(consist.stream()
                        .sorted(Comparator.comparingInt(ConsistCar::position))
                        .map(c -> {
                            Map<String, Object> m = new TreeMap<>();
                            m.put("position", c.position());
                            m.put("hazardClass", c.hazardClass());
                            return m;
                        }).toList()),
                hctx.ruleSet().version(), hctx.ruleSet().rulesHash(),
                toJson(pathSections),
                toJson(restrictions.stream()
                        .sorted(Comparator.comparing(SectionRestriction::sectionId))
                        .map(r -> {
                            Map<String, Object> m = new TreeMap<>();
                            m.put("sectionId", r.sectionId());
                            m.put("version", r.version());
                            m.put("allowedClasses", r.allowedClasses());
                            return m;
                        }).toList()),
                "PASS", operator, nowMillis);
    }

    private void commitReschedule(DayPlan oldPlan, DayPlan newPlan, List<ConsistCar> newConsist,
                                  HazardContext hctx, ValidationOutcome outcome, String operator,
                                  long nowMillis) {
        planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, nowMillis);
        planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, nowMillis);
        planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), nowMillis);
        insertSnapshot(newPlan, newConsist, hctx, outcome.pathSections(),
                outcome.restrictions(), operator, nowMillis);
    }

    private void assertRescheduleable(DayPlan oldPlan, DayPlan newPlan,
                                      BatchRescheduleItemRequest item, String oldKey,
                                      String newKey) {
        if (oldKey.equals(newKey)) {
            throw badRequest("新旧计划必须不同: " + oldKey);
        }
        if (oldPlan.status() != PlanStatus.PUBLISHED) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "仅已发布计划可改签，旧计划当前状态: " + oldPlan.status());
        }
        if (newPlan.status() != PlanStatus.DRAFT) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "改签新计划必须为草稿，当前状态: " + newPlan.status());
        }
        if (item.expectedOldVersion() != oldPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedOldVersion=" + item.expectedOldVersion()
                            + " 与旧计划当前版本 " + oldPlan.version() + " 不一致");
        }
        if (item.expectedNewVersion() != newPlan.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedNewVersion=" + item.expectedNewVersion()
                            + " 与新计划当前版本 " + newPlan.version() + " 不一致");
        }
        if (!oldPlan.opDate().equals(newPlan.opDate())) {
            throw conflict("OP_DATE_MISMATCH",
                    "新旧计划运营日必须相同: 旧=" + oldPlan.opDate() + " 新=" + newPlan.opDate());
        }
        if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
            throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldKey);
        }
        if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
            throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + newKey);
        }
    }

    /** 单改签版本/状态校验入参适配。 */
    private void assertRescheduleable(DayPlan oldPlan, DayPlan newPlan, RescheduleRequest item,
                                      String oldKey, String newKey) {
        assertRescheduleable(oldPlan, newPlan,
                new BatchRescheduleItemRequest(oldKey, item.newScheduleKey(),
                        item.expectedOldVersion(), item.expectedNewVersion()),
                oldKey, newKey);
    }

    private void assertBatchShape(List<BatchRescheduleItemRequest> items) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        TreeSet<String> oldKeys = new TreeSet<>();
        TreeSet<String> newKeys = new TreeSet<>();
        for (int i = 0; i < items.size(); i++) {
            BatchRescheduleItemRequest item = items.get(i);
            int index = i;
            if (item.oldScheduleKey().equals(item.newScheduleKey())) {
                conflicts.add(batchShapeDetail(index, item.oldScheduleKey(), "新旧计划必须不同"));
            }
            if (!oldKeys.add(item.oldScheduleKey())) {
                conflicts.add(batchShapeDetail(index, item.oldScheduleKey(), "oldScheduleKey 重复"));
            }
            if (!newKeys.add(item.newScheduleKey())) {
                conflicts.add(batchShapeDetail(index, item.newScheduleKey(), "newScheduleKey 重复"));
            }
        }
        for (int i = 0; i < items.size(); i++) {
            BatchRescheduleItemRequest item = items.get(i);
            int index = i;
            if (newKeys.contains(item.oldScheduleKey()) || oldKeys.contains(item.newScheduleKey())) {
                conflicts.add(batchShapeDetail(index, item.oldScheduleKey(),
                        "同一计划在批量改签中同时作为旧计划与新计划"));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_INVALID",
                    "批量改签项存在重复或交叉计划", conflicts);
        }
    }

    private Map<String, Object> batchShapeDetail(int index, String scheduleKey, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "BATCH_DUPLICATE_PLAN");
        d.put("itemIndex", index);
        d.put("scheduleKey", scheduleKey);
        d.put("reason", reason);
        return d;
    }

    private List<Map<String, Object>> withItem(List<Map<String, Object>> details, int index,
                                               BatchRescheduleItemRequest item) {
        List<Map<String, Object>> tagged = new ArrayList<>();
        for (Map<String, Object> d : details) {
            Map<String, Object> copy = new LinkedHashMap<>(d);
            copy.put("itemIndex", index);
            copy.put("oldScheduleKey", item.oldScheduleKey());
            copy.put("newScheduleKey", item.newScheduleKey());
            tagged.add(copy);
        }
        return tagged;
    }

    /**
     * 批量改签完整后态下两个新计划之间的同区段时间重叠检测。
     */
    private List<Map<String, Object>> findCrossPlanOverlaps(String keyA, List<Occupancy> occsA,
                                                            String keyB, List<Occupancy> occsB) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy a : occsA) {
            for (Occupancy b : occsB) {
                if (!a.sectionId().equals(b.sectionId())) {
                    continue;
                }
                if (a.startUtc().isBefore(b.endUtc()) && b.startUtc().isBefore(a.endUtc())) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("type", "SECTION_CONFLICT");
                    d.put("sectionId", a.sectionId());
                    d.put("scheduleKey", keyA);
                    d.put("conflictingScheduleKey", keyB);
                    d.put("trainNo", a.trainNo());
                    d.put("startUtc", a.startUtc().toString());
                    d.put("endUtc", a.endUtc().toString());
                    conflicts.add(d);
                }
            }
        }
        return conflicts;
    }

    @SafeVarargs
    private final List<Map<String, Object>> union(List<Map<String, Object>>... parts) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (List<Map<String, Object>> part : parts) {
            all.addAll(part);
        }
        return all;
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
     */
    private List<Map<String, Object>> findSectionConflicts(String scheduleKey, LocalDate opDate,
                                                           List<Occupancy> occupancies,
                                                           List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(opDate, sectionIds,
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
                    detail.put("scheduleKey", scheduleKey);
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

    // ---------- 幂等与指纹 ----------

    private Optional<PlanResponse> replayIfPresent(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash, PlanResponse.class);
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

    private PlanResponse resolveDuplicate(String opType, String requestKey, String hash) {
        return replayIfPresent(opType, requestKey, hash)
                .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在"));
    }

    private String fingerprint(Map<String, Object> parts) {
        return CanonicalFingerprint.sha256(
                CanonicalFingerprint.canonical(objectMapper, parts));
    }

    private Map<String, Object> fpCreate(CreatePlanRequest req) {
        Map<String, Object> m = new TreeMap<>();
        m.put("op", OP_CREATE);
        m.put("requestKey", req.requestKey());
        m.put("scheduleKey", req.scheduleKey());
        m.put("opDate", req.opDate().toString());
        m.put("occupancies", canonicalOccupancies(req.occupancies()));
        return m;
    }

    private Map<String, Object> fpUpdate(String scheduleKey, UpdateOccupanciesRequest req) {
        Map<String, Object> m = new TreeMap<>();
        m.put("op", OP_UPDATE);
        m.put("requestKey", req.requestKey());
        m.put("scheduleKey", scheduleKey);
        m.put("expectedVersion", req.expectedVersion());
        m.put("occupancies", canonicalOccupancies(req.occupancies()));
        return m;
    }

    private Map<String, Object> fpConsist(String scheduleKey, UpdateConsistRequest req) {
        Map<String, Object> m = new TreeMap<>();
        m.put("op", OP_CONSIST);
        m.put("requestKey", req.requestKey());
        m.put("scheduleKey", scheduleKey);
        m.put("expectedVersion", req.expectedVersion());
        List<Map<String, Object>> cars = req.cars().stream()
                .sorted(Comparator.comparing(ConsistCarRequest::position)
                        .thenComparing(c -> c.hazardClass() == null ? "" : c.hazardClass()))
                .map(c -> {
                    Map<String, Object> car = new TreeMap<>();
                    car.put("position", c.position());
                    car.put("hazardClass", c.hazardClass());
                    return car;
                }).toList();
        m.put("cars", cars);
        return m;
    }

    private Map<String, Object> fpAction(String opType, String scheduleKey, String operator) {
        Map<String, Object> m = new TreeMap<>();
        m.put("op", opType);
        m.put("scheduleKey", scheduleKey);
        m.put("operator", operator);
        return m;
    }

    private Map<String, Object> fpReschedule(String oldScheduleKey, RescheduleRequest req,
                                             String operator) {
        Map<String, Object> m = new TreeMap<>();
        m.put("op", OP_RESCHEDULE);
        m.put("requestKey", req.requestKey());
        m.put("operator", operator);
        m.put("oldScheduleKey", oldScheduleKey);
        m.put("newScheduleKey", req.newScheduleKey());
        m.put("expectedOldVersion", req.expectedOldVersion());
        m.put("expectedNewVersion", req.expectedNewVersion());
        return m;
    }

    private Map<String, Object> fpBatch(BatchRescheduleRequest req, String operator) {
        Map<String, Object> m = new TreeMap<>();
        m.put("op", OP_BATCH_RESCHEDULE);
        m.put("requestKey", req.requestKey());
        m.put("operator", operator);
        // 集合规范化：改签项按新旧业务键排序，使重排顺序的同参请求得到同一指纹
        List<Map<String, Object>> items = req.items().stream()
                .sorted(Comparator.comparing(BatchRescheduleItemRequest::oldScheduleKey)
                        .thenComparing(BatchRescheduleItemRequest::newScheduleKey))
                .map(it -> {
                    Map<String, Object> item = new TreeMap<>();
                    item.put("oldScheduleKey", it.oldScheduleKey());
                    item.put("newScheduleKey", it.newScheduleKey());
                    item.put("expectedOldVersion", it.expectedOldVersion());
                    item.put("expectedNewVersion", it.expectedNewVersion());
                    return item;
                }).toList();
        m.put("items", items);
        return m;
    }

    /**
     * 占用指纹：占用是有序清单（seq 影响存储顺序与响应），保持请求顺序；
     * 经规范化 JSON 转义，避免裸分隔符拼接。
     */
    private List<Map<String, Object>> canonicalOccupancies(List<OccupancyRequest> occupancies) {
        return occupancies.stream()
                .map(o -> {
                    Map<String, Object> occ = new TreeMap<>();
                    occ.put("trainNo", o.trainNo());
                    occ.put("sectionId", o.sectionId());
                    occ.put("startUtc", o.startUtc().toEpochMilli());
                    occ.put("endUtc", o.endUtc().toEpochMilli());
                    return occ;
                }).toList();
    }

    // ---------- 快照反序列化 ----------

    private List<ConsistCarView> readConsist(String json) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            return raw.stream()
                    .map(m -> new ConsistCarView(((Number) m.get("position")).intValue(),
                            (String) m.get("hazardClass")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("发布快照编组反序列化失败", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("发布快照路径反序列化失败", e);
        }
    }

    private List<SnapshotSectionView> readSnapshotSections(String json) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            return raw.stream()
                    .map(m -> new SnapshotSectionView((String) m.get("sectionId"),
                            ((Number) m.get("version")).intValue(),
                            readClasses(m.get("allowedClasses"))))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("发布快照区段限制反序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> readClasses(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<?>) value).stream().map(String::valueOf).toList();
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
     * 危险品校验上下文。
     */
    private record HazardContext(HazardRuleSet ruleSet) {
    }

    /**
     * 一次发布校验的中间结果。
     */
    private record ValidationOutcome(List<String> pathSections,
                                     List<SectionRestriction> restrictions,
                                     List<Map<String, Object>> slotConflicts,
                                     List<Map<String, Object>> hazardConflicts) {
    }
}
