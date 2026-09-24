package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.NightPair;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PairCancelRequest;
import com.example.starter.plan.web.dto.PairPublishRequest;
import com.example.starter.plan.web.dto.PairPublishResponse;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布、取消、原子改签、
 * 跨零点夜间计划对联合发布/取消与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布、改签与计划对联合发布经全局发布锁串行化，
 * 同一计划的更新/发布/取消/改签经行锁按事务提交顺序生效；
 * 仅成功结果写入幂等记录，失败（含 422 时隙冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 夜间跨零点窗口相对运营日的起点小时：22:00。 */
    private static final int NIGHT_START_HOUR = 22;
    /** 夜间跨零点窗口相对次日的终点小时：06:00。 */
    private static final int NIGHT_END_HOUR = 6;
    /** 单条夜间占用最大时长：8 小时。 */
    private static final Duration MAX_NIGHT_OCCUPANCY = Duration.ofHours(8);

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_PAIR_PUBLISH = "PAIR_PUBLISH";
    private static final String OP_PAIR_CANCEL = "PAIR_CANCEL";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。overnight=true 声明夜间跨零点草稿。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        boolean overnight = Boolean.TRUE.equals(req.overnight());
        validateOccupancyParams(req.occupancies(), req.opDate(), overnight);
        validateNightDeclaration(overnight, req.nightPairKey());
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
                String pairKey = req.nightPairKey() == null || req.nightPairKey().isBlank()
                        ? null : req.nightPairKey();
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT,
                        overnight, pairKey, now);
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
     * 夜间草稿替换后的占用仍须满足跨零点窗口规则。
     */
    public PlanResponse replaceOccupancies(String scheduleKey, UpdateOccupanciesRequest req) {
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
                validateOccupancyParams(req.occupancies(), plan.opDate(), plan.overnight());
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
     * 发布普通单日计划：全局发布锁内原子校验本计划列车重叠与跨计划区段重叠，
     * 任一冲突则整张计划保持草稿并抛出 422。夜间跨零点草稿必须走计划对联合发布。
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
                if (plan.nightPairKey() != null) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "夜间计划对成员必须通过 nightPairKey 计划对联合发布: " + scheduleKey);
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                List<LocalDate> days = List.of(plan.opDate());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(plan, occupancies, plan.opDate()));
                conflicts.addAll(findSectionConflicts(
                        List.of(new CandidatePlan(plan, occupancies)), days, List.of(plan.id())));
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
     * 夜间计划对联合发布：一个事务内重查两张草稿版本、各自列车内部重叠，
     * 以及两个运营日上同区段（含两张草稿相互之间）与其他已发布计划的重叠。
     * 跨零点占用同时参与当日与次日判定，仅端点相接不算冲突；
     * 任一张冲突或版本不符，两张都保持草稿。成功后两张同时发布并写入不可变计划对记录。
     */
    public PairPublishResponse publishPair(PairPublishRequest req) {
        String hash = hashPairPublish(req);
        Optional<PairPublishResponse> replay =
                replayIfPresent(OP_PAIR_PUBLISH, req.requestKey(), hash, PairPublishResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan sameDay = planRepo.findByKeyForUpdate(req.sameDayScheduleKey())
                        .orElseThrow(() -> notFound(req.sameDayScheduleKey()));
                DayPlan nextDay = planRepo.findByKeyForUpdate(req.nextDayScheduleKey())
                        .orElseThrow(() -> notFound(req.nextDayScheduleKey()));
                // 并发同键：锁等待后胜者可能已提交，两张都已发布时回放首次快照（同键改参仍 409）
                if (sameDay.status() == PlanStatus.PUBLISHED
                        && nextDay.status() == PlanStatus.PUBLISHED) {
                    return replayIfPresent(OP_PAIR_PUBLISH, req.requestKey(), hash,
                            PairPublishResponse.class)
                            .orElseThrow(() -> conflict("PLAN_STATE_CONFLICT",
                                    "计划对已发布: " + req.nightPairKey()));
                }
                assertPairDraftShape(req, sameDay, nextDay);
                List<Occupancy> sameOccs = planRepo.findOccupancies(sameDay.id());
                List<Occupancy> nextOccs = planRepo.findOccupancies(nextDay.id());

                LocalDate firstDay = sameDay.opDate();
                LocalDate secondDay = nextDay.opDate();
                List<LocalDate> days = List.of(firstDay, secondDay);
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(sameDay, sameOccs, firstDay));
                conflicts.addAll(findTrainOverlaps(nextDay, nextOccs, secondDay));
                conflicts.addAll(findSectionConflicts(
                        List.of(new CandidatePlan(sameDay, sameOccs),
                                new CandidatePlan(nextDay, nextOccs)),
                        days, List.of(sameDay.id(), nextDay.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "夜间计划对存在时隙冲突，两张计划均保持草稿", conflicts);
                }

                long now = System.currentTimeMillis();
                planRepo.insertNightPair(req.nightPairKey(), sameDay.id(), nextDay.id(), now);
                planRepo.updateStatus(sameDay.id(), PlanStatus.PUBLISHED, now);
                planRepo.updateStatus(nextDay.id(), PlanStatus.PUBLISHED, now);
                PairPublishResponse response = new PairPublishResponse(req.nightPairKey(),
                        loadPlan(req.sameDayScheduleKey()), loadPlan(req.nextDayScheduleKey()));
                idemRepo.insert(OP_PAIR_PUBLISH, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PAIR_PUBLISH, req.requestKey(), hash, PairPublishResponse.class)
                    .orElseThrow(() -> conflict("NIGHT_PAIR_CONFLICT",
                            "nightPairKey 已被其他计划对占用或成员已属于其他计划对: " + req.nightPairKey()));
        }
    }

    /**
     * 取消计划对中的单张成员：仅释放其自身占用，另一张保持已发布；
     * 不可变计划对记录保留不改写。
     */
    public PlanResponse cancelPairMember(PairCancelRequest req) {
        try {
            return tx.execute(status -> {
                DayPlan plan = planRepo.findByKeyForUpdate(req.scheduleKey())
                        .orElseThrow(() -> notFound(req.scheduleKey()));
                if (plan.nightPairKey() == null || plan.nightPairKey().isBlank()) {
                    throw conflict("NIGHT_PAIR_MISMATCH",
                            "计划不是夜间计划对成员: " + req.scheduleKey());
                }
                String nightPairKey = plan.nightPairKey();
                NightPair pair = planRepo.findNightPairByKey(nightPairKey)
                        .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NIGHT_PAIR_CONFLICT",
                                "夜间计划对尚未联合发布，不能按计划对取消: " + nightPairKey));
                // 成员可能已经过单张改签：沿改签链回溯到计划对记录中的原始成员 id 判定归属
                long rootPlanId = plan.id();
                while (true) {
                    Optional<RescheduleLink> incoming = planRepo.findLinkBySuccessor(rootPlanId);
                    if (incoming.isEmpty()) {
                        break;
                    }
                    rootPlanId = incoming.get().predecessorPlanId();
                }
                if (pair.sameDayPlanId() != rootPlanId && pair.nextDayPlanId() != rootPlanId) {
                    throw conflict("NIGHT_PAIR_MISMATCH",
                            "计划不属于该夜间计划对: " + req.scheduleKey() + " / " + nightPairKey);
                }
                String hash = sha256(OP_PAIR_CANCEL + '\n' + nightPairKey + '\n' + req.scheduleKey());
                Optional<PlanResponse> replay = replayIfPresent(OP_PAIR_CANCEL, req.requestKey(), hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布的计划对成员可取消，当前状态: " + plan.status());
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(plan.id(), PlanStatus.CANCELLED, now);
                PlanResponse response = loadPlan(req.scheduleKey());
                idemRepo.insert(OP_PAIR_CANCEL, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            DayPlan plan = planRepo.findByKey(req.scheduleKey())
                    .orElseThrow(() -> notFound(req.scheduleKey()));
            String hash = sha256(OP_PAIR_CANCEL + '\n' + plan.nightPairKey() + '\n' + req.scheduleKey());
            return replayIfPresent(OP_PAIR_CANCEL, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("NIGHT_PAIR_CONFLICT", "夜间计划对取消并发冲突"));
        }
    }

    /**
     * 原子改签：全局发布锁内校验新草稿（列车内部重叠与跨计划区段冲突，仅排除旧计划占用），
     * 通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 夜间计划改签时新草稿必须保持同一 nightPairKey，且跨零点占用同时参与两个运营日判定。
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
                assertNightPairKept(oldPlan, newPlan);
                if (planRepo.findLinkByPredecessor(oldPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "旧计划已存在直接后继: " + oldScheduleKey);
                }
                if (planRepo.findLinkBySuccessor(newPlan.id()).isPresent()) {
                    throw conflict("LINK_CONFLICT", "新计划已存在直接前驱: " + req.newScheduleKey());
                }
                List<Occupancy> occupancies = planRepo.findOccupancies(newPlan.id());
                List<LocalDate> days = newPlan.overnight()
                        ? List.of(newPlan.opDate(), newPlan.opDate().plusDays(1))
                        : List.of(newPlan.opDate());
                List<Map<String, Object>> conflicts = new ArrayList<>();
                conflicts.addAll(findTrainOverlaps(newPlan, occupancies, newPlan.opDate()));
                // 仅排除旧计划与自身占用，第三方（含夜间计划对的另一张在役成员）照常参与冲突裁决
                conflicts.addAll(findSectionConflicts(
                        List.of(new CandidatePlan(newPlan, occupancies)), days,
                        List.of(newPlan.id(), oldPlan.id())));
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "新草稿存在时隙冲突，改签未生效", conflicts);
                }
                long now = System.currentTimeMillis();
                planRepo.updateStatus(oldPlan.id(), PlanStatus.CANCELLED, now);
                planRepo.updateStatus(newPlan.id(), PlanStatus.PUBLISHED, now);
                planRepo.insertRescheduleLink(oldPlan.id(), newPlan.id(), now);
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
     * 查询指定运营日与区段上当前已发布的生效时隙；跨零点占用裁剪为落在该运营日
     * [dayStart, dayEnd) 内的部分，当日与前一日的夜间计划都可能贡献时隙。
     */
    public List<PublishedSlotView> getPublishedSlots(LocalDate opDate, String sectionId) {
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        return planRepo.findPublishedSlots(opDate, List.of(sectionId), List.of(-1L)).stream()
                .map(slot -> clip(slot.startUtc(), slot.endUtc(), dayStart, dayEnd)
                        .map(clipped -> new PublishedSlotView(slot.scheduleKey(), slot.trainNo(),
                                slot.sectionId(), clipped[0], clipped[1])))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(PublishedSlotView::startUtc))
                .toList();
    }

    // ---------- 内部实现 ----------

    /** 冲突检测中的候选计划（待发布草稿或改签新草稿）与其占用。 */
    private record CandidatePlan(DayPlan plan, List<Occupancy> occupancies) {
    }

    /** 参与某运营日区段判定的一条裁剪后区间；candidate 标记是否属于本次待发布候选。 */
    private record Interval(long planId, String scheduleKey, String sectionId,
                            Instant startUtc, Instant endUtc, boolean candidate) {
    }

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), plan.overnight(), plan.nightPairKey(), views);
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
     * 夜间声明一致性：overnight=true（当日跨零点草稿）必须携带 nightPairKey；
     * 非夜间草稿也允许携带 nightPairKey，表示计划对中的次日草稿；其余情况键必须为空。
     */
    private void validateNightDeclaration(boolean overnight, String nightPairKey) {
        if (overnight && (nightPairKey == null || nightPairKey.isBlank())) {
            throw badRequest("夜间跨零点草稿必须声明 nightPairKey");
        }
    }

    /**
     * 参数级校验。普通草稿：结束晚于开始、起止落在同一 Asia/Shanghai 日历日、
     * 完全包含于运营日。夜间草稿：每条占用落在运营日 22:00 至次日 06:00 窗口内，
     * 允许跨零点，单条时长不超过 8 小时。
     */
    private void validateOccupancyParams(List<OccupancyRequest> occupancies, LocalDate opDate,
                                         boolean overnight) {
        if (overnight) {
            Instant nightStart = opDate.atTime(NIGHT_START_HOUR, 0).atZone(OPERATION_ZONE).toInstant();
            Instant nightEnd = opDate.plusDays(1).atTime(NIGHT_END_HOUR, 0).atZone(OPERATION_ZONE).toInstant();
            for (int i = 0; i < occupancies.size(); i++) {
                OccupancyRequest o = occupancies.get(i);
                if (!o.endUtc().isAfter(o.startUtc())) {
                    throw badRequest("第 " + i + " 条占用结束时刻必须晚于开始时刻");
                }
                if (Duration.between(o.startUtc(), o.endUtc()).compareTo(MAX_NIGHT_OCCUPANCY) > 0) {
                    throw badRequest("第 " + i + " 条夜间占用时长不得超过 8 小时");
                }
                if (o.startUtc().isBefore(nightStart) || o.endUtc().isAfter(nightEnd)) {
                    throw badRequest("第 " + i + " 条夜间占用必须落在运营日 " + opDate
                            + " 22:00 至次日 06:00（Asia/Shanghai）窗口内");
                }
            }
            return;
        }
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
     * 计划对两张草稿的形态校验：均为夜间草稿、同一 nightPairKey（与请求一致）、
     * 运营日严格相邻、版本与请求期望一致。
     */
    private void assertPairDraftShape(PairPublishRequest req, DayPlan sameDay, DayPlan nextDay) {
        if (sameDay.status() != PlanStatus.DRAFT || nextDay.status() != PlanStatus.DRAFT) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "计划对两张计划都必须为草稿: "
                            + sameDay.scheduleKey() + "=" + sameDay.status() + ", "
                            + nextDay.scheduleKey() + "=" + nextDay.status());
        }
        if (!sameDay.overnight()) {
            throw conflict("NIGHT_PAIR_MISMATCH",
                    "计划对当日草稿必须声明 overnight: " + sameDay.scheduleKey());
        }
        if (nextDay.overnight()) {
            throw conflict("NIGHT_PAIR_MISMATCH",
                    "计划对次日草稿必须为普通（非跨零点）草稿: " + nextDay.scheduleKey());
        }
        if (sameDay.nightPairKey() == null
                || !req.nightPairKey().equals(sameDay.nightPairKey())
                || !req.nightPairKey().equals(nextDay.nightPairKey())) {
            throw conflict("NIGHT_PAIR_MISMATCH",
                    "两张草稿必须携带与请求一致的同一 nightPairKey: " + req.nightPairKey());
        }
        if (!nextDay.opDate().equals(sameDay.opDate().plusDays(1))) {
            throw conflict("NIGHT_PAIR_MISMATCH",
                    "计划对运营日必须严格相邻: " + sameDay.opDate() + " / " + nextDay.opDate());
        }
        if (req.expectedSameDayVersion() != sameDay.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedSameDayVersion=" + req.expectedSameDayVersion()
                            + " 与当日草稿当前版本 " + sameDay.version() + " 不一致");
        }
        if (req.expectedNextDayVersion() != nextDay.version()) {
            throw conflict("VERSION_CONFLICT",
                    "expectedNextDayVersion=" + req.expectedNextDayVersion()
                            + " 与次日草稿当前版本 " + nextDay.version() + " 不一致");
        }
    }

    /**
     * 改签时夜间计划对归属必须保持：成员（当日跨零点或次日普通）的新草稿必须携带同一
     * nightPairKey 且保持相同 overnight 角色；普通计划的新草稿不得携带计划对键。
     */
    private void assertNightPairKept(DayPlan oldPlan, DayPlan newPlan) {
        if (oldPlan.nightPairKey() != null) {
            if (newPlan.nightPairKey() == null
                    || !newPlan.nightPairKey().equals(oldPlan.nightPairKey())
                    || newPlan.overnight() != oldPlan.overnight()) {
                throw conflict("NIGHT_PAIR_MISMATCH",
                        "夜间计划改签后的新草稿必须保持同一 nightPairKey 与跨零点角色: "
                                + oldPlan.nightPairKey());
            }
        } else if (newPlan.nightPairKey() != null) {
            throw conflict("NIGHT_PAIR_MISMATCH",
                    "普通计划改签的新草稿不得声明夜间计划对: " + newPlan.scheduleKey());
        }
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findTrainOverlaps(DayPlan plan, List<Occupancy> occupancies,
                                                        LocalDate opDate) {
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
                    detail.put("opDate", opDate.toString());
                    detail.put("scheduleKey", plan.scheduleKey());
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
     * 在给定运营日集合上做同区段重叠裁决（左闭右开，仅端点相接不算冲突）。
     *
     * <p>每个候选计划的每条占用按运营日 [dayStart, dayEnd) 裁剪，跨零点占用因此同时
     * 出现在当日与次日；其他已发布计划（取运营日为当日或前一日者）同样裁剪后参与。
     * 候选计划相互之间（如夜间计划对的两张草稿）也按不同计划同区段重叠判冲突。
     *
     * @param candidates     待发布候选计划及其占用
     * @param days           需要裁决的运营日（按时间升序）
     * @param excludePlanIds 已发布时隙查询时排除的计划 id（候选自身/旧计划）
     */
    private List<Map<String, Object>> findSectionConflicts(List<CandidatePlan> candidates,
                                                           List<LocalDate> days,
                                                           List<Long> excludePlanIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (LocalDate day : days) {
            Instant dayStart = day.atStartOfDay(OPERATION_ZONE).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
            List<Interval> intervals = new ArrayList<>();
            for (CandidatePlan candidate : candidates) {
                for (Occupancy o : candidate.occupancies()) {
                    clip(o.startUtc(), o.endUtc(), dayStart, dayEnd).ifPresent(c ->
                            intervals.add(new Interval(candidate.plan().id(),
                                    candidate.plan().scheduleKey(), o.sectionId(), c[0], c[1], true)));
                }
            }
            List<String> sectionIds = intervals.stream().map(Interval::sectionId)
                    .collect(Collectors.toCollection(TreeSet::new)).stream().toList();
            for (PublishedSlot slot : planRepo.findPublishedSlots(day, sectionIds, excludePlanIds)) {
                clip(slot.startUtc(), slot.endUtc(), dayStart, dayEnd).ifPresent(c ->
                        intervals.add(new Interval(slot.planId(), slot.scheduleKey(),
                                slot.sectionId(), c[0], c[1], false)));
            }
            // 同区段、不同计划、左闭右开重叠；端点相接（cur.start == prev.end）合法
            Map<String, List<Interval>> bySection = new LinkedHashMap<>();
            for (Interval interval : intervals) {
                bySection.computeIfAbsent(interval.sectionId(), k -> new ArrayList<>()).add(interval);
            }
            bySection.forEach((sectionId, list) -> {
                List<Interval> sorted = list.stream()
                        .sorted(Comparator.comparing(Interval::startUtc)
                                .thenComparing(Interval::endUtc))
                        .toList();
                for (int i = 0; i < sorted.size(); i++) {
                    for (int j = i + 1; j < sorted.size(); j++) {
                        Interval a = sorted.get(i);
                        Interval b = sorted.get(j);
                        if (!b.startUtc().isBefore(a.endUtc())) {
                            break;
                        }
                        if (a.planId() == b.planId()) {
                            continue;
                        }
                        // 至少一方为候选时才上报；候选固定为 scheduleKey，对方为冲突计划
                        Interval cand;
                        Interval other;
                        if (a.candidate()) {
                            cand = a;
                            other = b;
                        } else if (b.candidate()) {
                            cand = b;
                            other = a;
                        } else {
                            continue;
                        }
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "SECTION_CONFLICT");
                        detail.put("opDate", day.toString());
                        detail.put("sectionId", sectionId);
                        detail.put("scheduleKey", cand.scheduleKey());
                        detail.put("conflictingScheduleKey", other.scheduleKey());
                        detail.put("startUtc", cand.startUtc().toString());
                        detail.put("endUtc", cand.endUtc().toString());
                        conflicts.add(detail);
                    }
                }
            });
        }
        return conflicts;
    }

    /**
     * 将 [start, end) 裁剪到 [dayStart, dayEnd)；无交集返回空。
     */
    private Optional<Instant[]> clip(Instant start, Instant end, Instant dayStart, Instant dayEnd) {
        Instant clippedStart = start.isBefore(dayStart) ? dayStart : start;
        Instant clippedEnd = end.isAfter(dayEnd) ? dayEnd : end;
        if (!clippedStart.isBefore(clippedEnd)) {
            return Optional.empty();
        }
        return Optional.of(new Instant[]{clippedStart, clippedEnd});
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
                .append(req.scheduleKey()).append('\n').append(req.opDate())
                .append('\n').append(Boolean.TRUE.equals(req.overnight()))
                .append('\n').append(req.nightPairKey() == null ? "" : req.nightPairKey());
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

    private String hashPairPublish(PairPublishRequest req) {
        return sha256(OP_PAIR_PUBLISH + '\n' + req.nightPairKey() + '\n'
                + req.sameDayScheduleKey() + '\n' + req.nextDayScheduleKey() + '\n'
                + req.expectedSameDayVersion() + '\n' + req.expectedNextDayVersion());
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
