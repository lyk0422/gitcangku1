package com.example.starter.plan.service;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionEvent;
import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.ConnectionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.BatchGuaranteeResponse;
import com.example.starter.plan.web.dto.ConnectionDiagnosticsView;
import com.example.starter.plan.web.dto.ConnectionEventView;
import com.example.starter.plan.web.dto.ConnectionRegisterRequest;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.OccupancyView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 铁路接续保障核心业务：接续登记/取消、前车改签后的接续重算与风险标记、
 * 批量保障改签，以及明细、历史与诊断查询。
 *
 * <p>并发与幂等约定与 PlanService 一致：写操作按 (操作类型, requestKey) 幂等，
 * 同键同参重放返回首次成功结果，同键不同参 409，失败不占键；登记、取消、批量保障
 * 与发布/改签共用全局发布锁，按事务提交顺序裁决。历史事件只追加不改写。
 */
@Service
public class ConnectionService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = PlanService.OPERATION_ZONE;

    /** 保障分钟数合法下限（含）。 */
    public static final int MIN_GUARANTEE_MINUTES = 0;

    /** 保障分钟数合法上限（含）。 */
    public static final int MAX_GUARANTEE_MINUTES = 1440;

    private static final String OP_CONNECTION_REGISTER = "CONNECTION_REGISTER";
    private static final String OP_CONNECTION_CANCEL = "CONNECTION_CANCEL";
    private static final String OP_BATCH_GUARANTEE = "BATCH_GUARANTEE";

    private static final String EVENT_REGISTERED = "REGISTERED";
    private static final String EVENT_CANCELLED = "CANCELLED";
    private static final String EVENT_RISK_MARKED = "RISK_MARKED";
    private static final String EVENT_RESCHEDULE_DECIDED = "RESCHEDULE_DECIDED";

    private final PlanRepository planRepo;
    private final ConnectionRepository connectionRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ConnectionService(PlanRepository planRepo, ConnectionRepository connectionRepo,
                             IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                             PlatformTransactionManager txManager, Clock clock) {
        this.planRepo = planRepo;
        this.connectionRepo = connectionRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 登记接续关系：两趟计划均须已发布；站点连续、保障分钟数在合法范围、
     * 同运营日或明确跨零点，且登记时保障分钟数已满足。任一不满足返回 422。
     * 同一前后车对已取消的接续重新登记时复活原行并更新参数，历史事件保留。
     */
    public ConnectionView register(ConnectionRegisterRequest req) {
        validateRegisterParams(req);
        String hash = hashRegister(req);
        Optional<ConnectionView> replay = replayIfPresent(OP_CONNECTION_REGISTER, req.requestKey(),
                hash, ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan pred = planRepo.findByKeyForUpdate(req.predecessorKey())
                        .orElseThrow(() -> notFoundPlan(req.predecessorKey()));
                DayPlan succ = planRepo.findByKeyForUpdate(req.successorKey())
                        .orElseThrow(() -> notFoundPlan(req.successorKey()));
                if (pred.id() == succ.id()) {
                    throw badRequest("前后车必须为不同计划: " + req.predecessorKey());
                }
                validateRegisterState(req, pred, succ);
                List<Occupancy> predOcc = planRepo.findOccupancies(pred.id());
                List<Occupancy> succOcc = planRepo.findOccupancies(succ.id());
                int actual = actualMinutes(predOcc, succOcc);
                if (actual < req.guaranteeMinutes()) {
                    throw guaranteeInsufficient("登记时接续保障不足", req.guaranteeMinutes(), actual);
                }
                long now = clock.millis();
                Optional<Connection> existing = connectionRepo.findByPair(pred.id(), succ.id());
                if (existing.isPresent() && existing.get().status() == ConnectionStatus.ACTIVE) {
                    throw conflict("CONNECTION_EXISTS", "接续已存在: " + existing.get().id());
                }
                Connection saved;
                if (existing.isPresent()) {
                    Connection c = existing.get();
                    saved = new Connection(c.id(), pred.id(), succ.id(), req.guaranteeMinutes(),
                            req.fromStation(), req.toStation(), req.crossesMidnight(),
                            ConnectionStatus.ACTIVE, c.createdAt(), now);
                    connectionRepo.update(saved);
                } else {
                    long id = connectionRepo.insert(new Connection(0L, pred.id(), succ.id(),
                            req.guaranteeMinutes(), req.fromStation(), req.toStation(),
                            req.crossesMidnight(), ConnectionStatus.ACTIVE, now, now));
                    saved = connectionRepo.findById(id).orElseThrow();
                }
                insertEvent(saved, EVENT_REGISTERED, pred, succ,
                        lastEnd(predOcc).toEpochMilli(), lastEnd(predOcc).toEpochMilli(),
                        req.guaranteeMinutes(), actual, null, null, now);
                ConnectionView view = toView(saved);
                idemRepo.insert(OP_CONNECTION_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONNECTION_REGISTER, req.requestKey(), hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_EXISTS", "接续已存在"));
        }
    }

    /**
     * 取消接续：仅置状态不删行，历史事件保留；取消后重算后车风险标记。
     */
    public ConnectionView cancel(long connectionId, String requestKey) {
        String hash = hashAction(OP_CONNECTION_CANCEL, String.valueOf(connectionId));
        Optional<ConnectionView> replay = replayIfPresent(OP_CONNECTION_CANCEL, requestKey,
                hash, ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Connection conn = connectionRepo.findByIdForUpdate(connectionId)
                        .orElseThrow(() -> notFoundConnection(connectionId));
                if (conn.status() == ConnectionStatus.CANCELLED) {
                    throw conflict("CONNECTION_STATE_CONFLICT", "接续已取消: " + connectionId);
                }
                long now = clock.millis();
                Connection saved = new Connection(conn.id(), conn.predecessorPlanId(),
                        conn.successorPlanId(), conn.guaranteeMinutes(), conn.fromStation(),
                        conn.toStation(), conn.crossesMidnight(), ConnectionStatus.CANCELLED,
                        conn.createdAt(), now);
                connectionRepo.update(saved);
                DayPlan pred = planRepo.findById(conn.predecessorPlanId()).orElseThrow();
                DayPlan succ = planRepo.findById(conn.successorPlanId()).orElseThrow();
                Instant predArrival = lastEnd(planRepo.findOccupancies(pred.id()));
                insertEvent(saved, EVENT_CANCELLED, pred, succ,
                        predArrival.toEpochMilli(), predArrival.toEpochMilli(),
                        null, null, null, null, now);
                refreshRiskMark(succ.id(), now);
                ConnectionView view = toView(saved);
                idemRepo.insert(OP_CONNECTION_CANCEL, requestKey, hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONNECTION_CANCEL, requestKey, hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_STATE_CONFLICT", "接续状态冲突"));
        }
    }
    /**
     * 前车（或后车）改签提交前在同一事务内重算其全部直接接续。
     * 生效接续跟随改签指向新计划；保障不足的后车若未开始则标记 CONNECTION_AT_RISK
     * 并生成可选改签时间窗，不自动修改后车时刻；历史事件只追加。
     * 须在持有发布锁与相关计划行锁的事务内调用（由 PlanService.reschedule 触发）。
     */
    public void recomputeAfterReschedule(DayPlan oldPlan, DayPlan newPlan) {
        long now = clock.millis();
        List<Connection> asPred = connectionRepo.findActiveByPredecessor(oldPlan.id());
        List<Connection> asSucc = connectionRepo.findActiveBySuccessor(oldPlan.id());
        for (Connection c : asPred) {
            connectionRepo.update(new Connection(c.id(), newPlan.id(), c.successorPlanId(),
                    c.guaranteeMinutes(), c.fromStation(), c.toStation(), c.crossesMidnight(),
                    c.status(), c.createdAt(), now));
        }
        for (Connection c : asSucc) {
            connectionRepo.update(new Connection(c.id(), c.predecessorPlanId(), newPlan.id(),
                    c.guaranteeMinutes(), c.fromStation(), c.toStation(), c.crossesMidnight(),
                    c.status(), c.createdAt(), now));
        }
        Instant oldArrival = lastEnd(planRepo.findOccupancies(oldPlan.id()));
        Set<Long> affectedSuccessors = new HashSet<>();
        for (Connection c : connectionRepo.findActiveByPredecessor(newPlan.id())) {
            DayPlan succ = planRepo.findByIdForUpdate(c.successorPlanId())
                    .orElseThrow(() -> new IllegalStateException("接续后车缺失: " + c));
            evaluateConnection(c, newPlan, succ, oldArrival, now);
            affectedSuccessors.add(succ.id());
        }
        for (Connection c : connectionRepo.findActiveBySuccessor(newPlan.id())) {
            DayPlan pred = planRepo.findById(c.predecessorPlanId())
                    .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + c));
            evaluateConnection(c, pred, newPlan, null, now);
        }
        for (Long succId : affectedSuccessors) {
            refreshRiskMark(succId, now);
        }
        if (!asSucc.isEmpty()) {
            refreshRiskMark(newPlan.id(), now);
        }
    }

    /**
     * 对选定的一组风险后车执行批量保障改签：同一事务内先为每个计划算出
     * 同时满足线路时隙与全部接续分钟数的新时刻，再整体提交；任一计划找不到
     * 不冲突时间窗时整次 422，已发布计划时刻、风险标记与时隙占用全部回滚。
     */
    public BatchGuaranteeResponse batchGuaranteeReschedule(BatchGuaranteeRequest req) {
        Set<String> seen = new HashSet<>();
        for (BatchGuaranteeRequest.Item item : req.items()) {
            if (!seen.add(item.scheduleKey())) {
                throw badRequest("批量改签包含重复计划: " + item.scheduleKey());
            }
        }
        String hash = hashBatch(req);
        Optional<BatchGuaranteeResponse> replay = replayIfPresent(OP_BATCH_GUARANTEE,
                req.requestKey(), hash, BatchGuaranteeResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                long now = clock.millis();
                Instant nowInstant = Instant.ofEpochMilli(now);
                // 1) 完整校验全部计划的当前状态
                List<DayPlan> plans = new ArrayList<>();
                Map<Long, List<Occupancy>> occupanciesByPlan = new HashMap<>();
                for (BatchGuaranteeRequest.Item item : req.items()) {
                    DayPlan plan = planRepo.findByKeyForUpdate(item.scheduleKey())
                            .orElseThrow(() -> notFoundPlan(item.scheduleKey()));
                    if (plan.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅已发布计划可保障改签，当前状态: " + plan.status());
                    }
                    if (item.expectedVersion() != plan.version()) {
                        throw conflict("VERSION_CONFLICT", "expectedVersion=" + item.expectedVersion()
                                + " 与当前版本 " + plan.version() + " 不一致");
                    }
                    if (plan.connectionRisk() != ConnectionRisk.CONNECTION_AT_RISK) {
                        throw unprocessable("NOT_AT_RISK",
                                "计划未处于接续风险状态: " + item.scheduleKey());
                    }
                    List<Occupancy> occ = planRepo.findOccupancies(plan.id());
                    if (!firstStart(occ).isAfter(nowInstant)) {
                        throw unprocessable("PLAN_ALREADY_STARTED",
                                "计划已开始，不可作为候选改签: " + item.scheduleKey());
                    }
                    plans.add(plan);
                    occupanciesByPlan.put(plan.id(), occ);
                }
                // 2) 按提交顺序为每个计划搜索可行平移量
                Set<Long> batchIds = new HashSet<>();
                plans.forEach(p -> batchIds.add(p.id()));
                Map<Long, Move> moves = new LinkedHashMap<>();
                List<PublishedSlot> batchNewSlots = new ArrayList<>();
                for (DayPlan plan : plans) {
                    List<Occupancy> occ = occupanciesByPlan.get(plan.id());
                    Instant departure = firstStart(occ);
                    Instant required = departure;
                    List<Connection> incoming = connectionRepo.findActiveBySuccessor(plan.id());
                    Connection binding = null;
                    Instant bindingArrival = null;
                    for (Connection c : incoming) {
                        Instant predArrival = arrivalOf(c.predecessorPlanId(), moves);
                        Instant need = predArrival.plus(Duration.ofMinutes(c.guaranteeMinutes()));
                        if (need.isAfter(required)) {
                            required = need;
                            binding = c;
                            bindingArrival = predArrival;
                        }
                    }
                    long initialDelta = Math.max(0L, required.toEpochMilli() - departure.toEpochMilli());
                    Long delta = findFeasibleShift(plan, occ, initialDelta, batchIds, batchNewSlots);
                    if (delta == null) {
                        int requiredMinutes = binding == null ? 0 : binding.guaranteeMinutes();
                        int actual = bindingArrival == null ? 0
                                : (int) Duration.between(bindingArrival, departure).toMinutes();
                        throw noFeasibleWindow(plan.scheduleKey(), requiredMinutes, actual);
                    }
                    List<Occupancy> shifted = shift(occ, delta);
                    Move move = new Move(plan, occ, shifted, delta);
                    moves.put(plan.id(), move);
                    shifted.forEach(o -> batchNewSlots.add(new PublishedSlot(plan.scheduleKey(),
                            o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc())));
                }
                // 3) 校验完整最终状态：全部接续分钟数（含作为前车的外向接续）
                for (Move move : moves.values()) {
                    Instant newDeparture = firstStart(move.shifted());
                    Instant newArrival = lastEnd(move.shifted());
                    for (Connection c : connectionRepo.findActiveBySuccessor(move.plan().id())) {
                        Instant predArrival = arrivalOf(c.predecessorPlanId(), moves);
                        long actual = Duration.between(predArrival, newDeparture).toMinutes();
                        if (actual < c.guaranteeMinutes()) {
                            throw noFeasibleWindow(move.plan().scheduleKey(),
                                    c.guaranteeMinutes(), (int) actual);
                        }
                    }
                    for (Connection c : connectionRepo.findActiveByPredecessor(move.plan().id())) {
                        Instant succDeparture = departureOf(c.successorPlanId(), moves);
                        long actual = Duration.between(newArrival, succDeparture).toMinutes();
                        if (actual < c.guaranteeMinutes()) {
                            throw noFeasibleWindow(move.plan().scheduleKey(),
                                    c.guaranteeMinutes(), (int) actual);
                        }
                    }
                }
                // 4) 应用：平移占用、版本加一、清除风险标记、固化改签决策事件
                List<BatchGuaranteeResponse.ItemResult> results = new ArrayList<>();
                for (Move move : moves.values()) {
                    DayPlan plan = move.plan();
                    planRepo.updateOccupancyTimes(move.shifted());
                    planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1,
                            PlanStatus.PUBLISHED, now);
                    planRepo.updateConnectionRisk(plan.id(), ConnectionRisk.NONE, now);
                    for (Connection c : connectionRepo.findActiveBySuccessor(plan.id())) {
                        DayPlan pred = planRepo.findById(c.predecessorPlanId()).orElseThrow();
                        int actual = (int) Duration.between(arrivalOf(c.predecessorPlanId(), moves),
                                firstStart(move.shifted())).toMinutes();
                        insertEvent(c, EVENT_RESCHEDULE_DECIDED, pred, plan,
                                firstStart(move.original()).toEpochMilli(),
                                firstStart(move.shifted()).toEpochMilli(),
                                c.guaranteeMinutes(), actual, null, null, now);
                    }
                    results.add(new BatchGuaranteeResponse.ItemResult(plan.scheduleKey(),
                            plan.version() + 1, firstStart(move.original()), firstStart(move.shifted()),
                            Duration.between(firstStart(move.original()), firstStart(move.shifted())).toMinutes(),
                            move.shifted().stream()
                                    .map(o -> new OccupancyView(o.trainNo(), o.sectionId(),
                                            o.startUtc(), o.endUtc()))
                                    .toList()));
                }
                // 被移动计划的后车接续可能因此解除风险，按当前状态刷新其标记
                for (Move move : moves.values()) {
                    for (Connection c : connectionRepo.findActiveByPredecessor(move.plan().id())) {
                        refreshRiskMark(c.successorPlanId(), now);
                    }
                }
                BatchGuaranteeResponse response = new BatchGuaranteeResponse(results);
                idemRepo.insert(OP_BATCH_GUARANTEE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_GUARANTEE, req.requestKey(), hash, BatchGuaranteeResponse.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED", "requestKey 冲突"));
        }
    }

    /** 一次批量改签中单个计划的计算结果。 */
    private record Move(DayPlan plan, List<Occupancy> original, List<Occupancy> shifted, long deltaMillis) {
    }
    /**
     * 重算单条接续：保障不足时固化 RISK_MARKED 历史事件（含前车版本、原新终到时刻、
     * 保障差额与可选改签时间窗）；已开始的后车只记录事件、不生成窗口也不进入候选。
     */
    private void evaluateConnection(Connection conn, DayPlan pred, DayPlan succ,
                                    Instant oldArrival, long now) {
        List<Occupancy> predOcc = planRepo.findOccupancies(pred.id());
        List<Occupancy> succOcc = planRepo.findOccupancies(succ.id());
        Instant predArrival = lastEnd(predOcc);
        Instant succDeparture = firstStart(succOcc);
        long actual = Duration.between(predArrival, succDeparture).toMinutes();
        if (actual >= conn.guaranteeMinutes()) {
            return;
        }
        boolean started = !succDeparture.isAfter(Instant.ofEpochMilli(now));
        Long[] window = started ? null : buildWindow(conn, predArrival, succ, succOcc);
        insertEvent(conn, EVENT_RISK_MARKED, pred, succ,
                oldArrival == null ? null : oldArrival.toEpochMilli(), predArrival.toEpochMilli(),
                conn.guaranteeMinutes(), (int) actual,
                window == null ? null : window[0], window == null ? null : window[1], now);
    }

    /**
     * 按当前全部生效接续重算后车风险标记：存在保障不足且计划未开始则标记
     * CONNECTION_AT_RISK，否则清除。只改当前标记，不改写历史事件。
     */
    private void refreshRiskMark(long successorPlanId, long now) {
        DayPlan succ = planRepo.findById(successorPlanId).orElseThrow();
        List<Occupancy> succOcc = planRepo.findOccupancies(successorPlanId);
        Instant succDeparture = firstStart(succOcc);
        boolean started = !succDeparture.isAfter(Instant.ofEpochMilli(now));
        boolean violated = false;
        for (Connection c : connectionRepo.findActiveBySuccessor(successorPlanId)) {
            Instant predArrival = lastEnd(planRepo.findOccupancies(c.predecessorPlanId()));
            if (Duration.between(predArrival, succDeparture).toMinutes() < c.guaranteeMinutes()) {
                violated = true;
                break;
            }
        }
        ConnectionRisk mark = violated && !started ? ConnectionRisk.CONNECTION_AT_RISK : ConnectionRisk.NONE;
        if (succ.connectionRisk() != mark) {
            planRepo.updateConnectionRisk(successorPlanId, mark, now);
        }
    }

    /**
     * 生成可选改签时间窗 [start, end)：start 为满足保障的最早始发时刻，
     * end 为保持占用不越出运营日的最晚始发时刻；运营日内无可行窗时返回 null。
     */
    private Long[] buildWindow(Connection conn, Instant predArrival, DayPlan succ,
                               List<Occupancy> succOcc) {
        Instant windowStart = predArrival.plus(Duration.ofMinutes(conn.guaranteeMinutes()));
        long durationMillis = lastEnd(succOcc).toEpochMilli() - firstStart(succOcc).toEpochMilli();
        Instant dayEnd = succ.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        Instant windowEnd = dayEnd.minusMillis(durationMillis);
        if (windowEnd.isBefore(windowStart)) {
            return null;
        }
        return new Long[]{windowStart.toEpochMilli(), windowEnd.toEpochMilli()};
    }

    /**
     * 为计划搜索满足时隙约束的最小平移量：从初始平移量出发，遇冲突则推到
     * 冲突时隙结束之后，直至无冲突；平移后占用越出运营日时返回 null。
     */
    private Long findFeasibleShift(DayPlan plan, List<Occupancy> occ, long initialDeltaMillis,
                                   Set<Long> excludePlanIds, List<PublishedSlot> batchNewSlots) {
        Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        long delta = initialDeltaMillis;
        for (int iter = 0; iter < 200; iter++) {
            List<Occupancy> shifted = shift(occ, delta);
            if (lastEnd(shifted).isAfter(dayEnd)) {
                return null;
            }
            List<String> sectionIds = shifted.stream().map(Occupancy::sectionId)
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                    .stream().toList();
            List<PublishedSlot> slots = new ArrayList<>(
                    planRepo.findPublishedSlots(plan.opDate(), sectionIds, excludePlanIds));
            slots.addAll(batchNewSlots);
            long needed = delta;
            boolean conflict = false;
            for (Occupancy o : shifted) {
                for (PublishedSlot s : slots) {
                    if (!o.sectionId().equals(s.sectionId())) {
                        continue;
                    }
                    boolean overlap = o.startUtc().isBefore(s.endUtc())
                            && s.startUtc().isBefore(o.endUtc());
                    if (overlap) {
                        conflict = true;
                        needed = Math.max(needed, s.endUtc().toEpochMilli() - o.startUtc().toEpochMilli() + delta);
                    }
                }
            }
            if (!conflict) {
                return delta;
            }
            delta = needed;
        }
        return null;
    }

    /** 占用整体平移（保持 id 与序号）。 */
    private List<Occupancy> shift(List<Occupancy> occ, long deltaMillis) {
        return occ.stream()
                .map(o -> new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                        o.startUtc().plusMillis(deltaMillis), o.endUtc().plusMillis(deltaMillis)))
                .toList();
    }

    /** 计划在批量计算中的终到时刻：已算出的用新值，否则用当前值。 */
    private Instant arrivalOf(long planId, Map<Long, Move> moves) {
        Move move = moves.get(planId);
        if (move != null) {
            return lastEnd(move.shifted());
        }
        return lastEnd(planRepo.findOccupancies(planId));
    }

    /** 计划在批量计算中的始发时刻：已算出的用新值，否则用当前值。 */
    private Instant departureOf(long planId, Map<Long, Move> moves) {
        Move move = moves.get(planId);
        if (move != null) {
            return firstStart(move.shifted());
        }
        return firstStart(planRepo.findOccupancies(planId));
    }

    // ---------- 查询（只读，不改变状态） ----------

    /**
     * 接续明细，不存在返回 404。
     */
    public ConnectionView getConnection(long connectionId) {
        Connection conn = connectionRepo.findById(connectionId)
                .orElseThrow(() -> notFoundConnection(connectionId));
        return toView(conn);
    }

    /**
     * 按计划业务键过滤接续列表（任一端参与）；两个过滤条件可同时给出。
     */
    public List<ConnectionView> listConnections(String predecessorKey, String successorKey) {
        List<Connection> result;
        if (predecessorKey != null && successorKey != null) {
            DayPlan pred = planRepo.findByKey(predecessorKey)
                    .orElseThrow(() -> notFoundPlan(predecessorKey));
            DayPlan succ = planRepo.findByKey(successorKey)
                    .orElseThrow(() -> notFoundPlan(successorKey));
            result = connectionRepo.findByPair(pred.id(), succ.id()).map(List::of)
                    .orElse(List.of());
        } else if (predecessorKey != null) {
            DayPlan pred = planRepo.findByKey(predecessorKey)
                    .orElseThrow(() -> notFoundPlan(predecessorKey));
            result = connectionRepo.findByPlan(pred.id()).stream()
                    .filter(c -> c.predecessorPlanId() == pred.id()).toList();
        } else if (successorKey != null) {
            DayPlan succ = planRepo.findByKey(successorKey)
                    .orElseThrow(() -> notFoundPlan(successorKey));
            result = connectionRepo.findByPlan(succ.id()).stream()
                    .filter(c -> c.successorPlanId() == succ.id()).toList();
        } else {
            throw badRequest("至少提供一个过滤条件: predecessorKey 或 successorKey");
        }
        return result.stream().map(this::toView).toList();
    }

    /**
     * 接续历史事件（只追加不改写），按发生顺序返回；接续不存在返回 404。
     */
    public List<ConnectionEventView> getHistory(long connectionId) {
        connectionRepo.findById(connectionId)
                .orElseThrow(() -> notFoundConnection(connectionId));
        return connectionRepo.findEvents(connectionId).stream()
                .map(this::toEventView)
                .toList();
    }

    /**
     * 计划接续诊断：当前风险标记、参与的全部接续（含实时保障差额）与未解除的风险时间窗。
     */
    public ConnectionDiagnosticsView getDiagnostics(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFoundPlan(scheduleKey));
        List<ConnectionView> connections = connectionRepo.findByPlan(plan.id()).stream()
                .map(this::toView)
                .toList();
        List<ConnectionDiagnosticsView.RiskWindow> windows = new ArrayList<>();
        if (plan.connectionRisk() == ConnectionRisk.CONNECTION_AT_RISK) {
            for (Connection c : connectionRepo.findActiveBySuccessor(plan.id())) {
                List<ConnectionEvent> events = connectionRepo.findEvents(c.id());
                for (int i = events.size() - 1; i >= 0; i--) {
                    ConnectionEvent e = events.get(i);
                    if (EVENT_RISK_MARKED.equals(e.eventType()) && e.windowStartUtc() != null) {
                        DayPlan pred = planRepo.findById(c.predecessorPlanId()).orElseThrow();
                        windows.add(new ConnectionDiagnosticsView.RiskWindow(c.id(),
                                pred.scheduleKey(), Instant.ofEpochMilli(e.windowStartUtc()),
                                Instant.ofEpochMilli(e.windowEndUtc()),
                                e.requiredMinutes(), e.actualMinutes(), e.deficitMinutes()));
                        break;
                    }
                }
            }
        }
        return new ConnectionDiagnosticsView(plan.scheduleKey(), plan.status().name(),
                plan.connectionRisk().name(), connections, windows);
    }
    // ---------- 内部实现 ----------

    private void validateRegisterParams(ConnectionRegisterRequest req) {
        int minutes = req.guaranteeMinutes();
        if (minutes < MIN_GUARANTEE_MINUTES || minutes > MAX_GUARANTEE_MINUTES) {
            throw unprocessable("INVALID_GUARANTEE_MINUTES",
                    "保障分钟数须在 " + MIN_GUARANTEE_MINUTES + "~" + MAX_GUARANTEE_MINUTES
                            + " 之间: " + minutes);
        }
    }

    private void validateRegisterState(ConnectionRegisterRequest req, DayPlan pred, DayPlan succ) {
        if (pred.status() == PlanStatus.CANCELLED || succ.status() == PlanStatus.CANCELLED) {
            throw unprocessable("PLAN_CANCELLED", "计划已取消，不可登记接续");
        }
        if (pred.status() != PlanStatus.PUBLISHED || succ.status() != PlanStatus.PUBLISHED) {
            throw unprocessable("PLAN_NOT_PUBLISHED", "仅已发布计划可登记接续");
        }
        if (pred.opDate().equals(succ.opDate())) {
            if (req.crossesMidnight()) {
                throw unprocessable("OP_DATE_MISMATCH", "同运营日接续不得声明跨零点");
            }
        } else if (succ.opDate().equals(pred.opDate().plusDays(1))) {
            if (!req.crossesMidnight()) {
                throw unprocessable("OP_DATE_MISMATCH", "跨零点接续必须显式声明 crossesMidnight");
            }
        } else {
            throw unprocessable("OP_DATE_MISMATCH",
                    "接续计划必须同运营日或明确跨零点: 前车=" + pred.opDate() + " 后车=" + succ.opDate());
        }
        if (!req.fromStation().equals(req.toStation())) {
            throw unprocessable("STATION_DISCONTINUITY",
                    "站点不连续: 终到站 " + req.fromStation() + " 与始发站 " + req.toStation() + " 不一致");
        }
    }

    private void insertEvent(Connection conn, String eventType, DayPlan pred, DayPlan succ,
                             Long oldTimeUtc, Long newTimeUtc,
                             Integer requiredMinutes, Integer actualMinutes,
                             Long windowStartUtc, Long windowEndUtc, long now) {
        Integer deficit = requiredMinutes == null || actualMinutes == null
                ? null : requiredMinutes - actualMinutes;
        connectionRepo.insertEvent(new ConnectionEvent(0L, conn.id(), eventType,
                pred.id(), succ.id(), pred.version(), oldTimeUtc, newTimeUtc,
                requiredMinutes, actualMinutes, deficit, windowStartUtc, windowEndUtc, now));
    }

    private ConnectionView toView(Connection conn) {
        DayPlan pred = planRepo.findById(conn.predecessorPlanId()).orElseThrow();
        DayPlan succ = planRepo.findById(conn.successorPlanId()).orElseThrow();
        int actual = actualMinutes(planRepo.findOccupancies(pred.id()),
                planRepo.findOccupancies(succ.id()));
        return new ConnectionView(conn.id(), pred.scheduleKey(), succ.scheduleKey(),
                conn.guaranteeMinutes(), conn.fromStation(), conn.toStation(),
                conn.crossesMidnight(), conn.status().name(), actual,
                conn.guaranteeMinutes(), conn.guaranteeMinutes() - actual,
                Instant.ofEpochMilli(conn.createdAt()));
    }

    private ConnectionEventView toEventView(ConnectionEvent e) {
        String predKey = planRepo.findById(e.predecessorPlanId()).orElseThrow().scheduleKey();
        String succKey = planRepo.findById(e.successorPlanId()).orElseThrow().scheduleKey();
        return new ConnectionEventView(e.id(), e.connectionId(), e.eventType(), predKey, succKey,
                e.predecessorVersion(),
                e.oldTimeUtc() == null ? null : Instant.ofEpochMilli(e.oldTimeUtc()),
                e.newTimeUtc() == null ? null : Instant.ofEpochMilli(e.newTimeUtc()),
                e.requiredMinutes(), e.actualMinutes(), e.deficitMinutes(),
                e.windowStartUtc() == null ? null : Instant.ofEpochMilli(e.windowStartUtc()),
                e.windowEndUtc() == null ? null : Instant.ofEpochMilli(e.windowEndUtc()),
                Instant.ofEpochMilli(e.createdAt()));
    }

    /** 实际保障分钟数：后车首次始发时刻减前车最后终到时刻（可为负）。 */
    private int actualMinutes(List<Occupancy> predOcc, List<Occupancy> succOcc) {
        return (int) Duration.between(lastEnd(predOcc), firstStart(succOcc)).toMinutes();
    }

    /** 计划始发时刻：全部占用中最早的开始时刻。 */
    private static Instant firstStart(List<Occupancy> occ) {
        return occ.stream().map(Occupancy::startUtc).min(Instant::compareTo).orElseThrow();
    }

    /** 计划终到时刻：全部占用中最晚的结束时刻。 */
    private static Instant lastEnd(List<Occupancy> occ) {
        return occ.stream().map(Occupancy::endUtc).max(Instant::compareTo).orElseThrow();
    }

    private String hashRegister(ConnectionRegisterRequest req) {
        return sha256(OP_CONNECTION_REGISTER + '\n' + req.predecessorKey() + '\n'
                + req.successorKey() + '\n' + req.guaranteeMinutes() + '\n'
                + req.fromStation() + '\n' + req.toStation() + '\n' + req.crossesMidnight());
    }

    private String hashAction(String opType, String target) {
        return sha256(opType + '\n' + target);
    }

    private String hashBatch(BatchGuaranteeRequest req) {
        StringBuilder sb = new StringBuilder(OP_BATCH_GUARANTEE);
        for (BatchGuaranteeRequest.Item item : req.items()) {
            sb.append('\n').append(item.scheduleKey()).append('|').append(item.expectedVersion());
        }
        return sha256(sb.toString());
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
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

    private ApiException notFoundPlan(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException notFoundConnection(long connectionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND",
                "接续不存在: " + connectionId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /**
     * 接续保障不足 422，明细携带要求值、实际值与差额。
     */
    private ApiException guaranteeInsufficient(String message, int requiredMinutes, int actualMinutes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "CONNECTION_GUARANTEE_INSUFFICIENT");
        detail.put("requiredMinutes", requiredMinutes);
        detail.put("actualMinutes", actualMinutes);
        detail.put("deficitMinutes", requiredMinutes - actualMinutes);
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "CONNECTION_GUARANTEE_INSUFFICIENT", message, List.of(detail));
    }

    /**
     * 批量保障改签找不到可行时间窗 422，明细携带计划、要求值、实际值与差额。
     */
    private ApiException noFeasibleWindow(String scheduleKey, int requiredMinutes, int actualMinutes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "NO_FEASIBLE_WINDOW");
        detail.put("scheduleKey", scheduleKey);
        detail.put("requiredMinutes", requiredMinutes);
        detail.put("actualMinutes", actualMinutes);
        detail.put("deficitMinutes", requiredMinutes - actualMinutes);
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_FEASIBLE_WINDOW",
                "计划找不到不冲突且满足全部接续分钟数的时间窗: " + scheduleKey, List.of(detail));
    }
}