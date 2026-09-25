package com.example.starter.plan.service;

import com.example.starter.plan.model.ChainBreak;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.model.RollingStock;
import com.example.starter.plan.model.StockSegment;
import com.example.starter.plan.repo.ChainBreakRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RollingStockRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchPublishRequest;
import com.example.starter.plan.web.dto.BatchPublishResponse;
import com.example.starter.plan.web.dto.ChainBreakView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainItem;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.RollingStockView;
import com.example.starter.plan.web.dto.StockChainItem;
import com.example.starter.plan.web.dto.StockChainResponse;
import com.example.starter.plan.web.dto.TurnaroundUpdateRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
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
 * 铁路走廊日计划核心业务：草稿创建/整体替换、发布（单张/整批）、取消、原子改签、
 * 车底交路衔接校验、周转参数版本化修改与查询。
 *
 * <p>并发与幂等约定：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次成功结果，
 * 同键不同参返回 409；发布、改签、取消与周转参数修改经全局写锁串行化，按事务提交顺序裁决；
 * 仅成功结果写入幂等记录，失败（含 422 时隙/交路冲突）不缓存、可修正后重试。
 */
@Service
public class PlanService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    private static final String OP_CREATE = "CREATE";
    private static final String OP_UPDATE = "UPDATE";
    private static final String OP_PUBLISH = "PUBLISH";
    private static final String OP_PUBLISH_BATCH = "PUBLISH_BATCH";
    private static final String OP_CANCEL = "CANCEL";
    private static final String OP_RESCHEDULE = "RESCHEDULE";
    private static final String OP_TURNAROUND_UPDATE = "TURNAROUND_UPDATE";

    /** 断链原因：取消交路中间段。 */
    public static final String BREAK_REASON_CANCEL_MIDDLE = "CANCEL_MIDDLE";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final RollingStockRepository stockRepo;
    private final ChainBreakRepository breakRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlanService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                       RollingStockRepository stockRepo, ChainBreakRepository breakRepo,
                       ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.stockRepo = stockRepo;
        this.breakRepo = breakRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建草稿计划（版本 1，状态 DRAFT）。
     */
    public PlanResponse createDraft(CreatePlanRequest req) {
        validateOccupancyParams(req.occupancies());
        validateWithinOperationDay(req.occupancies(), req.opDate());
        String[] stock = normalizeStock(req.stockNo(), req.originStation(), req.destinationStation());
        String hash = hashCreate(req, stock);
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
                long planId = planRepo.insertPlan(req.scheduleKey(), req.opDate(), PlanStatus.DRAFT,
                        stock[0], stock[1], stock[2], now);
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
     * 发布计划：全局写锁内原子校验本计划列车重叠、跨计划区段重叠与车底交路衔接，
     * 任一冲突则整张计划保持草稿并抛出 422（携带冲突区段、计划或交路断点）。
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
                List<DayPlan> publishing = List.of(plan);
                validateSlotConflicts(publishing, List.of(plan.id()));
                validateChainLinks(publishing, List.of(plan.id()));
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
     * 整批发布：同批草稿在同一事务内统一校验时隙冲突与车底交路衔接（含同批段之间），
     * 任一段不合法整单回滚，已发布计划与时隙占用不变。
     */
    public BatchPublishResponse publishBatch(BatchPublishRequest req) {
        List<String> keys = req.scheduleKeys().stream().distinct().toList();
        if (keys.size() != req.scheduleKeys().size()) {
            throw badRequest("整批发布的 scheduleKey 不得重复");
        }
        String hash = hashBatch(req);
        Optional<BatchPublishResponse> replay =
                replayIfPresent(OP_PUBLISH_BATCH, req.requestKey(), hash, BatchPublishResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 按主键排序加锁，避免并发事务交叉加锁死锁
                List<DayPlan> plans = planRepo.findByKeysForUpdate(keys).stream()
                        .sorted(Comparator.comparingLong(DayPlan::id))
                        .toList();
                Map<String, DayPlan> byKey = new LinkedHashMap<>();
                plans.forEach(p -> byKey.put(p.scheduleKey(), p));
                for (String key : keys) {
                    DayPlan plan = byKey.get(key);
                    if (plan == null) {
                        throw notFound(key);
                    }
                    if (plan.status() != PlanStatus.DRAFT) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅草稿可发布，计划 " + key + " 当前状态: " + plan.status());
                    }
                }
                List<Long> planIds = plans.stream().map(DayPlan::id).toList();
                validateSlotConflicts(plans, planIds);
                validateChainLinks(plans, planIds);
                long now = System.currentTimeMillis();
                for (DayPlan plan : plans) {
                    planRepo.updateStatus(plan.id(), PlanStatus.PUBLISHED, now);
                }
                BatchPublishResponse response = new BatchPublishResponse(
                        keys.stream().map(this::loadPlan).toList());
                idemRepo.insert(OP_PUBLISH_BATCH, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PUBLISH_BATCH, req.requestKey(), hash,
                            BatchPublishResponse.class)
                    .orElseThrow(() -> conflict("PLAN_STATE_CONFLICT", "整批发布并发冲突"));
        }
    }

    /**
     * 取消已发布计划：时隙立即释放，历史计划与占用保留不改写。
     * 取消车底交路中间段时允许链断裂，但追加不可变断链记录，并把后续段标记为待重排。
     */
    public PlanResponse cancel(String scheduleKey, String requestKey) {
        String hash = hashAction(OP_CANCEL, scheduleKey);
        Optional<PlanResponse> replay = replayIfPresent(OP_CANCEL, requestKey, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan plan = planRepo.findByKeyForUpdate(scheduleKey)
                        .orElseThrow(() -> notFound(scheduleKey));
                if (plan.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可取消，当前状态: " + plan.status());
                }
                long now = System.currentTimeMillis();
                if (plan.stockNo() != null) {
                    recordBreakAndMarkPending(plan, now);
                }
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
     * 原子改签：全局写锁内校验新草稿（列车内部重叠、跨计划区段冲突与车底交路衔接，
     * 仅排除旧计划占用），通过后同一事务取消旧计划、发布新计划并追加不可变前后继关联。
     * 交路衔接（含周转不足）不满足返回 422，旧计划状态不变，整笔回滚。
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
                DayPlan oldPlan = lockPlan(oldScheduleKey);
                DayPlan newPlan = lockPlan(req.newScheduleKey());
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
                validateSlotConflicts(List.of(newPlan), List.of(newPlan.id(), oldPlan.id()));
                // 新段并入车底交路链：旧段在同事务内被取消，从既有链中剔除
                validateChainLinks(List.of(newPlan), List.of(newPlan.id(), oldPlan.id()));
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
     * 登记或修改车底最小周转分钟数（1～240）。
     *
     * <p>expectedVersion=0 表示首次登记（成功后版本 1）；已存在车底须携带当前版本，冲突 409。
     * 修改在同一事务内按新参数重校验该车底全部已发布相邻段，任一处不满足返回 422 并列出违规段，
     * 参数不做部分生效。
     */
    public RollingStockView updateTurnaround(String stockNo, TurnaroundUpdateRequest req) {
        String normalizedStock = normalizeStation(stockNo);
        if (normalizedStock == null) {
            throw badRequest("stockNo 不能为空");
        }
        if (req.expectedVersion() == null || req.expectedVersion() < 0) {
            throw badRequest("expectedVersion 不能为空且不能为负");
        }
        if (req.minTurnaroundMinutes() == null
                || req.minTurnaroundMinutes() < 1 || req.minTurnaroundMinutes() > 240) {
            throw badRequest("最小周转分钟数取值范围为 1～240");
        }
        String hash = hashTurnaround(normalizedStock, req);
        Optional<RollingStockView> replay =
                replayIfPresent(OP_TURNAROUND_UPDATE, req.requestKey(), hash, RollingStockView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        int newMinutes = req.minTurnaroundMinutes();
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<RollingStock> existing = stockRepo.findByStockNoForUpdate(normalizedStock);
                RollingStock stock;
                if (existing.isEmpty()) {
                    if (req.expectedVersion() != 0) {
                        throw notFoundStock(normalizedStock);
                    }
                    stock = stockRepo.insert(normalizedStock, newMinutes, System.currentTimeMillis());
                } else {
                    stock = existing.get();
                    if (req.expectedVersion() != stock.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与车底当前版本 " + stock.version() + " 不一致");
                    }
                    stockRepo.updateTurnaround(stock.id(), newMinutes, stock.version() + 1,
                            System.currentTimeMillis());
                    stock = new RollingStock(stock.id(), normalizedStock, newMinutes,
                            stock.version() + 1);
                }
                // 按新参数全量重校验该车底全部已发布相邻段
                List<Map<String, Object>> violations = ChainRules.validatePublished(
                        toRuleSegments(planRepo.findPublishedSegments(normalizedStock)), newMinutes);
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TURNAROUND_CONFLICT",
                            "新周转参数下列车底存在不满足的相邻段，参数未生效", violations);
                }
                RollingStockView view = new RollingStockView(normalizedStock, newMinutes,
                        stock.version());
                idemRepo.insert(OP_TURNAROUND_UPDATE, req.requestKey(), hash, toJson(view),
                        System.currentTimeMillis());
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TURNAROUND_UPDATE, req.requestKey(), hash,
                            RollingStockView.class)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "车底并发登记冲突: "
                            + normalizedStock));
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
     * 查询车底交路链明细（按运营日分组、日内按始发时刻升序）与全部不可变断链记录。
     * 车底未登记返回 404。
     */
    public StockChainResponse getStockChain(String stockNo) {
        String normalizedStock = normalizeStation(stockNo);
        if (normalizedStock == null) {
            throw badRequest("stockNo 不能为空");
        }
        RollingStock stock = stockRepo.findByStockNo(normalizedStock)
                .orElseThrow(() -> notFoundStock(normalizedStock));
        List<StockSegment> all = planRepo.findPublishedSegments(normalizedStock);
        List<ChainBreak> breaks = breakRepo.findByStockNo(normalizedStock);
        Set<Long> breakSuccessorIds = breaks.stream()
                .map(ChainBreak::successorPlanId)
                .collect(java.util.stream.Collectors.toSet());
        Map<LocalDate, List<StockSegment>> byDate = new LinkedHashMap<>();
        for (StockSegment s : all) {
            byDate.computeIfAbsent(s.opDate(), k -> new ArrayList<>()).add(s);
        }
        List<StockChainResponse.StockChainDay> days = new ArrayList<>();
        byDate.forEach((date, segments) -> {
            List<StockChainItem> items = new ArrayList<>();
            StockSegment prev = null;
            for (StockSegment cur : segments) {
                Long gap = null;
                Long required = null;
                boolean linked = true;
                if (prev != null) {
                    gap = ChainRules.gapMinutes(toRuleSegment(prev), toRuleSegment(cur));
                    required = (long) stock.minTurnaroundMinutes();
                    linked = prev.destinationStation().equals(cur.originStation())
                            && gap >= stock.minTurnaroundMinutes();
                }
                items.add(new StockChainItem(cur.scheduleKey(), date.toString(),
                        PlanStatus.PUBLISHED.name(), cur.originStation(), cur.destinationStation(),
                        cur.startUtc(), cur.endUtc(), gap, required, linked,
                        breakSuccessorIds.contains(cur.planId()), cur.rearrangePending(),
                        planVersion(cur.planId())));
                prev = cur;
            }
            days.add(new StockChainResponse.StockChainDay(date.toString(), items));
        });
        List<ChainBreakView> breakViews = breaks.stream()
                .map(b -> new ChainBreakView(b.id(), b.stockNo(), b.opDate().toString(),
                        scheduleKeyOf(b.cancelledPlanId()), scheduleKeyOf(b.predecessorPlanId()),
                        scheduleKeyOf(b.successorPlanId()), b.reason(), b.createdAt()))
                .toList();
        return new StockChainResponse(normalizedStock, stock.minTurnaroundMinutes(),
                stock.version(), days, breakViews);
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

    // ---------- 内部实现：校验 ----------

    /**
     * 时隙冲突校验：计划内同列车重叠 + 与其他已发布计划的跨计划区段重叠。
     *
     * @param publishing     本次拟发布的全部计划（整批时多于一个）
     * @param excludePlanIds 跨计划检测排除的计划 id（发布为自身集合；改签为新旧两个）
     */
    private void validateSlotConflicts(List<DayPlan> publishing, List<Long> excludePlanIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<Long, List<Occupancy>> occupanciesByPlan = new LinkedHashMap<>();
        for (DayPlan plan : publishing) {
            List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
            occupanciesByPlan.put(plan.id(), occupancies);
            conflicts.addAll(findTrainOverlaps(plan.scheduleKey(), occupancies));
            conflicts.addAll(findSectionConflicts(plan, occupancies, excludePlanIds));
        }
        // 同批不同计划之间也不允许占用同一区段的重叠时隙（左闭右开，相邻合法）
        List<DayPlan> ordered = publishing.stream()
                .sorted(Comparator.comparingLong(DayPlan::id)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            DayPlan plan = ordered.get(i);
            List<Occupancy> occupancies = occupanciesByPlan.get(plan.id());
            for (int j = i + 1; j < ordered.size(); j++) {
                DayPlan other = ordered.get(j);
                if (!plan.opDate().equals(other.opDate())) {
                    continue;
                }
                conflicts.addAll(findPairwiseSectionConflicts(plan, occupancies,
                        other, occupanciesByPlan.get(other.id())));
            }
        }
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                    "存在时隙冲突，计划保持草稿", conflicts);
        }
    }

    /**
     * 检测两张同批计划在同一区段上的占用重叠（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findPairwiseSectionConflicts(DayPlan plan,
                                                                   List<Occupancy> occupancies,
                                                                   DayPlan other,
                                                                   List<Occupancy> otherOccupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (Occupancy p : otherOccupancies) {
                if (!o.sectionId().equals(p.sectionId())) {
                    continue;
                }
                boolean overlap = o.startUtc().isBefore(p.endUtc())
                        && p.startUtc().isBefore(o.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", other.scheduleKey());
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
     * 车底交路衔接校验：拟发布段按车底分组并入该车底同运营日已发布链，
     * 逐对检查站点衔接与最小周转间隔；车底未登记或存在断点均抛 422。
     *
     * @param excludePlanIds 既有链查询时排除的计划 id（如改签中的旧段）
     */
    private void validateChainLinks(List<DayPlan> publishing, List<Long> excludePlanIds) {
        Map<String, List<DayPlan>> byStock = new LinkedHashMap<>();
        for (DayPlan plan : publishing) {
            if (plan.stockNo() != null) {
                byStock.computeIfAbsent(plan.stockNo(), k -> new ArrayList<>()).add(plan);
            }
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Map.Entry<String, List<DayPlan>> entry : byStock.entrySet()) {
            String stockNo = entry.getKey();
            RollingStock stock = stockRepo.findByStockNo(stockNo)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "STOCK_NOT_FOUND", "车底未登记最小周转参数: " + stockNo));
            List<DayPlan> candidates = entry.getValue();
            List<Long> excludes = new ArrayList<>(excludePlanIds);
            candidates.stream().map(DayPlan::id).forEach(excludes::add);
            Map<LocalDate, List<ChainRules.Segment>> existingByDate = new LinkedHashMap<>();
            for (DayPlan candidate : candidates) {
                existingByDate.computeIfAbsent(candidate.opDate(), k ->
                        planRepo.findPublishedSegments(stockNo, candidate.opDate(), excludes).stream()
                                .map(this::toRuleSegment)
                                .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
            }
            List<ChainRules.Segment> existing = existingByDate.values().stream()
                    .flatMap(List::stream)
                    .toList();
            List<ChainRules.Segment> candidateSegments = candidates.stream()
                    .map(this::toRuleSegment)
                    .toList();
            violations.addAll(ChainRules.validateMerged(existing, candidateSegments,
                    stock.minTurnaroundMinutes()));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CHAIN_LINK_CONFLICT",
                    "车底交路衔接不满足（站点不衔接或周转不足），计划保持草稿", violations);
        }
    }

    /**
     * 取消交路中间段：定位同车底同运营日的时间序前后相邻段，
     * 两者均存在时追加不可变断链记录，并把被取消段之后的全部段标记为待重排。
     */
    private void recordBreakAndMarkPending(DayPlan cancelled, long nowMillis) {
        List<Occupancy> occupancies = planRepo.findOccupancies(cancelled.id());
        Instant cancelledStart = occupancies.stream().map(Occupancy::startUtc)
                .min(Comparator.naturalOrder()).orElseThrow();
        List<StockSegment> neighbors = planRepo.findPublishedSegments(
                cancelled.stockNo(), cancelled.opDate(), List.of(cancelled.id()));
        StockSegment predecessor = null;
        StockSegment successor = null;
        for (StockSegment s : neighbors) {
            if (s.startUtc().isBefore(cancelledStart)
                    || (s.startUtc().equals(cancelledStart) && s.planId() < cancelled.id())) {
                if (predecessor == null || s.startUtc().isAfter(predecessor.startUtc())
                        || (s.startUtc().equals(predecessor.startUtc())
                        && s.planId() > predecessor.planId())) {
                    predecessor = s;
                }
            } else if (successor == null || s.startUtc().isBefore(successor.startUtc())
                    || (s.startUtc().equals(successor.startUtc())
                    && s.planId() < successor.planId())) {
                successor = s;
            }
        }
        if (predecessor != null && successor != null) {
            breakRepo.insert(cancelled.stockNo(), cancelled.opDate(), cancelled.id(),
                    predecessor.planId(), successor.planId(),
                    BREAK_REASON_CANCEL_MIDDLE, nowMillis);
            Instant successorStart = successor.startUtc();
            for (StockSegment s : neighbors) {
                boolean after = s.startUtc().isAfter(successorStart)
                        || (s.startUtc().equals(successorStart) && s.planId() >= successor.planId());
                if (after) {
                    planRepo.updateRearrangePending(s.planId(), true, nowMillis);
                }
            }
        }
    }

    // ---------- 内部实现：装配与工具 ----------

    private DayPlan lockPlan(String scheduleKey) {
        return planRepo.findByKeyForUpdate(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
    }

    private PlanResponse loadPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<OccupancyView> views = planRepo.findOccupancies(plan.id()).stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
        return new PlanResponse(plan.scheduleKey(), plan.opDate(), plan.version(),
                plan.status().name(), plan.stockNo(), plan.originStation(),
                plan.destinationStation(), plan.rearrangePending(), views);
    }

    private int planVersion(long planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> new IllegalStateException("交路段计划缺失: id=" + planId))
                .version();
    }

    private String scheduleKeyOf(long planId) {
        return planRepo.findById(planId)
                .map(DayPlan::scheduleKey)
                .orElseThrow(() -> new IllegalStateException("断链关联计划缺失: id=" + planId));
    }

    private ChainRules.Segment toRuleSegment(DayPlan plan) {
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        Instant start = occupancies.stream().map(Occupancy::startUtc)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> badRequest("计划占用为空: " + plan.scheduleKey()));
        Instant end = occupancies.stream().map(Occupancy::endUtc)
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> badRequest("计划占用为空: " + plan.scheduleKey()));
        return new ChainRules.Segment(plan.id(), plan.scheduleKey(), plan.opDate(),
                plan.originStation(), plan.destinationStation(), start, end);
    }

    private ChainRules.Segment toRuleSegment(StockSegment s) {
        return new ChainRules.Segment(s.planId(), s.scheduleKey(), s.opDate(),
                s.originStation(), s.destinationStation(), s.startUtc(), s.endUtc());
    }

    private List<ChainRules.Segment> toRuleSegments(List<StockSegment> segments) {
        return segments.stream().map(this::toRuleSegment).toList();
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
     * 车底登记三要素要么全部提供，要么全部为空；返回规范化后的 [stockNo, origin, destination]，
     * 未登记时三个元素均为 null。
     */
    private String[] normalizeStock(String stockNo, String originStation, String destinationStation) {
        String stock = normalizeStation(stockNo);
        String origin = normalizeStation(originStation);
        String destination = normalizeStation(destinationStation);
        int provided = (stock == null ? 0 : 1) + (origin == null ? 0 : 1)
                + (destination == null ? 0 : 1);
        if (provided != 0 && provided != 3) {
            throw badRequest("车底标识、始发站、终到站必须同时提供或同时为空");
        }
        return new String[] {stock, origin, destination};
    }

    private String normalizeStation(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    private String hashCreate(CreatePlanRequest req, String[] stock) {
        StringBuilder sb = new StringBuilder(OP_CREATE).append('\n')
                .append(req.scheduleKey()).append('\n').append(req.opDate())
                .append('\n').append(stock[0] == null ? "" : stock[0])
                .append('|').append(stock[1] == null ? "" : stock[1])
                .append('|').append(stock[2] == null ? "" : stock[2]);
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

    private String hashBatch(BatchPublishRequest req) {
        return sha256(OP_PUBLISH_BATCH + '\n' + String.join(",", req.scheduleKeys()));
    }

    private String hashReschedule(String oldScheduleKey, RescheduleRequest req) {
        return sha256(OP_RESCHEDULE + '\n' + oldScheduleKey + '\n' + req.newScheduleKey()
                + '\n' + req.expectedOldVersion() + '\n' + req.expectedNewVersion());
    }

    private String hashTurnaround(String stockNo, TurnaroundUpdateRequest req) {
        return sha256(OP_TURNAROUND_UPDATE + '\n' + stockNo + '\n' + req.expectedVersion()
                + '\n' + req.minTurnaroundMinutes());
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

    private ApiException notFoundStock(String stockNo) {
        return new ApiException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND",
                "车底不存在: " + stockNo);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
