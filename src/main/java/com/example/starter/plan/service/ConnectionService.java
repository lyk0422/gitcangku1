package com.example.starter.plan.service;

import com.example.starter.plan.model.ConnectionEvent;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RailConnection;
import com.example.starter.plan.repo.ConnectionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConnectionDeficitView;
import com.example.starter.plan.web.dto.ConnectionEventView;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.GuaranteeRescheduleItem;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleResponse;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * 铁路旅客接续保障：接续登记/取消、改签触发的同事务风险重估、批量保障改签与诊断查询。
 *
 * <p>规则要点：后车始发须不早于前车终到加保障分钟数（0~720）；接续计划须同运营日或明确跨零点；
 * 前车改签延后时在同一事务内重算全部直接接续，保障不足且未开始的后车标记 CONNECTION_AT_RISK
 * 并生成可选改签时间窗（不自动改时刻）；批量保障改签须同时满足线路时隙与全部接续分钟数，
 * 任一计划找不到不冲突时间窗则整次 422 回滚。所有判定以事件形式固化（只插不改）。
 *
 * <p>并发与幂等约定与 PlanService 一致：全部写操作经全局发布锁串行化，按提交顺序裁决；
 * requestKey 同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class ConnectionService {

    /** 运营日解释时区（与 PlanService 一致）。 */
    private static final ZoneId OPERATION_ZONE = PlanService.OPERATION_ZONE;

    private static final String OP_CONN_REGISTER = "CONN_REGISTER";
    private static final String OP_CONN_CANCEL = "CONN_CANCEL";
    private static final String OP_GUARANTEE_RESCHEDULE = "GUARANTEE_RESCHEDULE";

    /** 保障分钟数合法下限（含）。 */
    public static final int MIN_GUARANTEE_MINUTES = 0;
    /** 保障分钟数合法上限（含）。 */
    public static final int MAX_GUARANTEE_MINUTES = 720;

    private static final long MINUTE_MILLIS = 60_000L;

    private final PlanRepository planRepo;
    private final ConnectionRepository connRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final TimeProvider timeProvider;

    public ConnectionService(PlanRepository planRepo, ConnectionRepository connRepo,
                             IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                             PlatformTransactionManager txManager, TimeProvider timeProvider) {
        this.planRepo = planRepo;
        this.connRepo = connRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.timeProvider = timeProvider;
    }

    /**
     * 登记接续关系：两趟已发布计划、站点连续、同运营日或明确跨零点、保障分钟数充足。
     * 违反业务规则返回 422（携带实际值/要求值/差额）；同对计划重复登记（含已取消）返回 409。
     */
    public ConnectionView register(RegisterConnectionRequest req) {
        if (req.predecessorScheduleKey().equals(req.successorScheduleKey())) {
            throw badRequest("前车与后车计划必须不同: " + req.predecessorScheduleKey());
        }
        validateGuaranteeRange(req.guaranteeMinutes());
        String hash = sha256(OP_CONN_REGISTER + '\n' + req.predecessorScheduleKey() + '\n'
                + req.successorScheduleKey() + '\n' + req.guaranteeMinutes() + '\n' + req.crossMidnight());
        Optional<ConnectionView> replay = replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash,
                ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 并发下同键请求在此串行：已完成者直接重放首次结果
                Optional<ConnectionView> inTxReplay = replayIfPresent(OP_CONN_REGISTER,
                        req.requestKey(), hash, ConnectionView.class);
                if (inTxReplay.isPresent()) {
                    return inTxReplay.get();
                }
                DayPlan pred = planRepo.findByKeyForUpdate(req.predecessorScheduleKey())
                        .orElseThrow(() -> notFound(req.predecessorScheduleKey()));
                DayPlan succ = planRepo.findByKeyForUpdate(req.successorScheduleKey())
                        .orElseThrow(() -> notFound(req.successorScheduleKey()));
                validateRegistrable(pred, succ);
                List<Occupancy> predOccs = planRepo.findOccupancies(pred.id());
                List<Occupancy> succOccs = planRepo.findOccupancies(succ.id());
                validateStationContinuity(pred, predOccs, succ, succOccs);
                validateOperationDays(pred, succ, req.crossMidnight());
                long predArrival = arrivalOf(predOccs);
                long succDeparture = departureOf(succOccs);
                long actualMinutes = minutesBetween(predArrival, succDeparture);
                long deficit = req.guaranteeMinutes() - actualMinutes;
                if (deficit > 0) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("predecessorScheduleKey", pred.scheduleKey());
                    detail.put("successorScheduleKey", succ.scheduleKey());
                    detail.put("predecessorArrivalUtc", Instant.ofEpochMilli(predArrival).toString());
                    detail.put("successorDepartureUtc", Instant.ofEpochMilli(succDeparture).toString());
                    detail.put("requiredMinutes", req.guaranteeMinutes());
                    detail.put("actualMinutes", actualMinutes);
                    detail.put("deficitMinutes", deficit);
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GUARANTEE_INSUFFICIENT",
                            "接续保障不足：要求 " + req.guaranteeMinutes() + " 分钟，实际 " + actualMinutes
                                    + " 分钟，差额 " + deficit + " 分钟", List.of(detail));
                }
                if (connRepo.findByPair(pred.id(), succ.id()).isPresent()) {
                    throw conflict("CONNECTION_EXISTS",
                            "两计划间已存在接续关系（含已取消，不可重复登记）: " + pred.scheduleKey()
                                    + " -> " + succ.scheduleKey());
                }
                long now = timeProvider.nowMillis();
                long connectionId = connRepo.insertConnection(pred.id(), succ.id(),
                        req.guaranteeMinutes(), req.crossMidnight(), now);
                connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_REGISTERED,
                        connectionId, pred.id(), succ.id(), pred.version(), predArrival, succDeparture,
                        null, req.guaranteeMinutes(), actualMinutes, deficit, null, null, now));
                ConnectionView view = toView(connRepo.findById(connectionId).orElseThrow());
                idemRepo.insert(OP_CONN_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_EXISTS", "两计划间已存在接续关系"));
        }
    }

    /**
     * 取消生效中的接续：历史风险记录不改写；取消后在同一事务内重估后车风险标记。
     */
    public ConnectionView cancelConnection(long connectionId, String requestKey) {
        String hash = sha256(OP_CONN_CANCEL + '\n' + connectionId);
        Optional<ConnectionView> replay = replayIfPresent(OP_CONN_CANCEL, requestKey, hash,
                ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<ConnectionView> inTxReplay = replayIfPresent(OP_CONN_CANCEL, requestKey,
                        hash, ConnectionView.class);
                if (inTxReplay.isPresent()) {
                    return inTxReplay.get();
                }
                RailConnection conn = connRepo.findByIdForUpdate(connectionId)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "CONNECTION_NOT_FOUND", "接续不存在: " + connectionId));
                if (!RailConnection.STATUS_ACTIVE.equals(conn.status())) {
                    throw conflict("CONNECTION_STATE_CONFLICT",
                            "仅生效中的接续可取消，当前状态: " + conn.status());
                }
                long now = timeProvider.nowMillis();
                connRepo.markCancelled(connectionId, now);
                Evaluation eval = evaluate(conn);
                connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_CANCELLED,
                        connectionId, conn.predecessorPlanId(), conn.successorPlanId(),
                        eval.pred().version(), eval.predArrivalMs(), eval.succDepartureMs(), null,
                        conn.guaranteeMinutes(), eval.actualMinutes(), eval.deficitMinutes(),
                        null, null, now));
                reevaluateSuccessor(conn.successorPlanId());
                ConnectionView view = toView(connRepo.findById(connectionId).orElseThrow());
                idemRepo.insert(OP_CONN_CANCEL, requestKey, hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_STATE_CONFLICT", "接续状态冲突"));
        }
    }

    /**
     * 批量保障改签：对选定的一组 CONNECTION_AT_RISK 后车，在同一事务内整体延后至
     * 同时满足线路时隙与全部接续分钟数（含其作为前车的下游接续）的时刻。
     * 先校验完整最终状态再提交；任一计划找不到不冲突时间窗则整次 422，
     * 已发布计划时刻、风险标记与时隙占用均回滚。
     */
    public GuaranteeRescheduleResponse guaranteeReschedule(GuaranteeRescheduleRequest req) {
        if (new HashSet<>(req.scheduleKeys()).size() != req.scheduleKeys().size()) {
            throw badRequest("scheduleKeys 存在重复计划");
        }
        String hash = sha256(OP_GUARANTEE_RESCHEDULE + '\n' + String.join("\n", req.scheduleKeys()));
        Optional<GuaranteeRescheduleResponse> replay = replayIfPresent(OP_GUARANTEE_RESCHEDULE,
                req.requestKey(), hash, GuaranteeRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<GuaranteeRescheduleResponse> inTxReplay = replayIfPresent(
                        OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash,
                        GuaranteeRescheduleResponse.class);
                if (inTxReplay.isPresent()) {
                    return inTxReplay.get();
                }
                List<DayPlan> plans = new ArrayList<>();
                for (String scheduleKey : req.scheduleKeys()) {
                    plans.add(planRepo.findByKeyForUpdate(scheduleKey)
                            .orElseThrow(() -> notFound(scheduleKey)));
                }
                Set<Long> batchIds = new HashSet<>();
                Map<Long, List<Occupancy>> occupanciesByPlan = new HashMap<>();
                for (DayPlan plan : plans) {
                    batchIds.add(plan.id());
                    occupanciesByPlan.put(plan.id(), planRepo.findOccupancies(plan.id()));
                }
                for (DayPlan plan : plans) {
                    validateGuaranteeCandidate(plan, occupanciesByPlan.get(plan.id()));
                }
                // 逐计划计算最终新时刻（后续计划可见先处理计划的顺延结果），全部可行才提交
                Map<Long, List<Occupancy>> shiftedByPlan = new LinkedHashMap<>();
                Map<Long, PublishedSlot> tentativeSlots = new HashMap<>();
                List<ShiftDecision> decisions = new ArrayList<>();
                for (DayPlan plan : plans) {
                    List<Occupancy> occs = occupanciesByPlan.get(plan.id());
                    ShiftDecision decision = computeShift(plan, occs, batchIds, shiftedByPlan,
                            tentativeSlots, occupanciesByPlan);
                    decisions.add(decision);
                    List<Occupancy> shifted = shiftOccupancies(occs, decision.shiftMs());
                    shiftedByPlan.put(plan.id(), shifted);
                    for (Occupancy o : shifted) {
                        tentativeSlots.put(o.id(), new PublishedSlot(plan.scheduleKey(), o.trainNo(),
                                o.sectionId(), o.startUtc(), o.endUtc()));
                    }
                }
                long now = timeProvider.nowMillis();
                List<GuaranteeRescheduleItem> items = new ArrayList<>();
                for (int i = 0; i < plans.size(); i++) {
                    DayPlan plan = plans.get(i);
                    ShiftDecision decision = decisions.get(i);
                    List<Occupancy> shifted = shiftedByPlan.get(plan.id());
                    planRepo.replaceOccupancies(plan.id(), shifted);
                    planRepo.updateVersionAndStatus(plan.id(), plan.version() + 1,
                            PlanStatus.PUBLISHED, now);
                    for (InboundEvaluation inbound : decision.inboundEvaluations()) {
                        connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_RESCHEDULED,
                                inbound.conn().id(), inbound.conn().predecessorPlanId(), plan.id(),
                                inbound.predVersionAfterBatch(), inbound.predArrivalMs(),
                                decision.newDepartureMs(), decision.prevDepartureMs(),
                                inbound.conn().guaranteeMinutes(), inbound.actualMinutes(),
                                inbound.deficitMinutes(), null, null, now));
                    }
                    items.add(new GuaranteeRescheduleItem(plan.scheduleKey(), plan.version() + 1,
                            Instant.ofEpochMilli(decision.prevDepartureMs()),
                            Instant.ofEpochMilli(decision.newDepartureMs()),
                            Math.floorDiv(decision.shiftMs(), MINUTE_MILLIS),
                            DayPlan.RISK_NONE));
                }
                // 同事务重估：风险标记解除并固化 RISK_CLEARED；下游接续同步重算
                for (DayPlan plan : plans) {
                    reevaluateConnectionsOf(plan.id());
                }
                GuaranteeRescheduleResponse response = new GuaranteeRescheduleResponse(items);
                idemRepo.insert(OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash,
                    GuaranteeRescheduleResponse.class)
                    .orElseThrow(() -> conflict("PLAN_STATE_CONFLICT", "批量保障改签状态冲突"));
        }
    }

    /**
     * 改签同事务钩子：ACTIVE 接续随计划迁移至新计划，并重算全部直接接续的风险。
     * 由 PlanService 在改签事务内调用，失败随主事务回滚。
     */
    public void afterReschedule(DayPlan oldPlan, DayPlan newPlan) {
        long now = timeProvider.nowMillis();
        for (RailConnection conn : connRepo.findActiveByPredecessor(oldPlan.id())) {
            if (connRepo.findByPair(newPlan.id(), conn.successorPlanId()).isPresent()) {
                cancelSuperseded(conn, now);
            } else {
                connRepo.updatePredecessor(conn.id(), newPlan.id());
            }
        }
        for (RailConnection conn : connRepo.findActiveBySuccessor(oldPlan.id())) {
            if (connRepo.findByPair(conn.predecessorPlanId(), newPlan.id()).isPresent()) {
                cancelSuperseded(conn, now);
            } else {
                connRepo.updateSuccessor(conn.id(), newPlan.id());
            }
        }
        reevaluateConnectionsOf(newPlan.id());
    }

    /**
     * 计划取消同事务钩子：计划参与的 ACTIVE 接续全部取消（固化 CANCELLED 事件），
     * 对侧后车风险标记同步重估；被取消计划自身的风险标记清除（不再是改签候选）。
     * 由 PlanService 在取消事务内调用，失败随主事务回滚。
     */
    public void afterPlanCancelled(DayPlan plan) {
        long now = timeProvider.nowMillis();
        Set<Long> affectedSuccessors = new LinkedHashSet<>();
        for (RailConnection conn : connRepo.findActiveInvolving(plan.id())) {
            connRepo.markCancelled(conn.id(), now);
            Evaluation eval = evaluate(conn);
            connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_CANCELLED,
                    conn.id(), conn.predecessorPlanId(), conn.successorPlanId(),
                    eval.pred().version(), eval.predArrivalMs(), eval.succDepartureMs(), null,
                    conn.guaranteeMinutes(), eval.actualMinutes(), eval.deficitMinutes(),
                    null, null, now));
            if (conn.successorPlanId() != plan.id()) {
                affectedSuccessors.add(conn.successorPlanId());
            }
        }
        if (DayPlan.RISK_CONNECTION_AT_RISK.equals(plan.riskStatus())) {
            planRepo.updateRiskStatus(plan.id(), DayPlan.RISK_NONE, now);
        }
        for (Long successorId : affectedSuccessors) {
            reevaluateSuccessor(successorId);
        }
    }

    /**
     * 接续明细查询（只读）。
     */
    public ConnectionView getConnection(long connectionId) {
        RailConnection conn = connRepo.findById(connectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CONNECTION_NOT_FOUND", "接续不存在: " + connectionId));
        return toView(conn);
    }

    /**
     * 查询指定计划参与（前车或后车）的全部接续（含已取消），按登记顺序（只读）。
     */
    public List<ConnectionView> listConnectionsForPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<RailConnection> conns = new ArrayList<>(connRepo.findActiveInvolving(plan.id()));
        // 已取消的接续不再属于 ACTIVE，补充查询历史
        conns.addAll(connRepo.findCancelledInvolving(plan.id()));
        conns.sort(java.util.Comparator.comparingLong(RailConnection::id));
        return conns.stream().map(this::toView).toList();
    }

    /**
     * 查询指定计划参与的全部接续事件（历史业务证据），按发生顺序（只读，不改变状态）。
     */
    public List<ConnectionEventView> listEventsForPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        Map<Long, String> keyCache = new HashMap<>();
        return connRepo.findEventsByPlan(plan.id()).stream()
                .map(e -> toEventView(e, keyCache))
                .toList();
    }

    /**
     * 计划接续风险诊断：当前标记、可选改签时间窗（风险存续期）与各生效接续的实时评估（只读）。
     */
    public ConnectionRiskView getRisk(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        List<RailConnection> inbound = connRepo.findActiveBySuccessor(plan.id());
        List<Evaluation> evals = inbound.stream().map(this::evaluate).toList();
        List<ConnectionDeficitView> deficits = evals.stream()
                .map(e -> new ConnectionDeficitView(e.conn().id(), e.pred().scheduleKey(),
                        e.conn().guaranteeMinutes(), e.actualMinutes(), e.deficitMinutes(),
                        e.deficitMinutes() <= 0))
                .toList();
        Instant windowStart = null;
        Instant windowEnd = null;
        if (DayPlan.RISK_CONNECTION_AT_RISK.equals(plan.riskStatus()) && !evals.isEmpty()) {
            long[] window = computeWindow(plan, evals);
            windowStart = Instant.ofEpochMilli(window[0]);
            windowEnd = Instant.ofEpochMilli(window[1]);
        }
        return new ConnectionRiskView(plan.scheduleKey(), plan.riskStatus(), windowStart, windowEnd,
                deficits);
    }

    // ---------- 内部实现 ----------

    /**
     * 接续实时评估：固化前车版本、前后车时刻与保障差额的计算基值。
     */
    private record Evaluation(RailConnection conn, DayPlan pred, DayPlan succ,
                              long predArrivalMs, long succDepartureMs,
                              long actualMinutes, long deficitMinutes) {
    }

    /**
     * 批量保障改签中一条入向接续的评估快照（含批次内生效的前车版本）。
     */
    private record InboundEvaluation(RailConnection conn, int predVersionAfterBatch,
                                     long predArrivalMs, long actualMinutes, long deficitMinutes) {
    }

    /**
     * 单计划平移决策。
     */
    private record ShiftDecision(long prevDepartureMs, long newDepartureMs, long shiftMs,
                                 List<InboundEvaluation> inboundEvaluations) {
    }

    private Evaluation evaluate(RailConnection conn) {
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车计划缺失: " + conn));
        DayPlan succ = planRepo.findById(conn.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续后车计划缺失: " + conn));
        long predArrival = arrivalOf(planRepo.findOccupancies(pred.id()));
        long succDeparture = departureOf(planRepo.findOccupancies(succ.id()));
        long actual = minutesBetween(predArrival, succDeparture);
        return new Evaluation(conn, pred, succ, predArrival, succDeparture, actual,
                conn.guaranteeMinutes() - actual);
    }

    /**
     * 重算指定计划参与的全部直接接续，并按后车分组更新风险标记（同事务内调用）。
     */
    private void reevaluateConnectionsOf(long planId) {
        Set<Long> successorIds = new LinkedHashSet<>();
        for (RailConnection conn : connRepo.findActiveInvolving(planId)) {
            successorIds.add(conn.successorPlanId());
        }
        for (Long successorId : successorIds) {
            reevaluateSuccessor(successorId);
        }
    }

    /**
     * 重估单个后车：任一生效接续保障不足且计划未开始则标记 CONNECTION_AT_RISK 并固化
     * RISK_MARKED（含可选改签时间窗）；全部恢复充足且曾标记则解除并固化 RISK_CLEARED。
     * 已开始计划不可自动标记为候选改签。历史事件只增不改。
     */
    private void reevaluateSuccessor(long successorPlanId) {
        Optional<DayPlan> maybeSucc = planRepo.findById(successorPlanId);
        if (maybeSucc.isEmpty()) {
            return;
        }
        DayPlan succ = maybeSucc.get();
        long now = timeProvider.nowMillis();
        if (succ.status() != PlanStatus.PUBLISHED) {
            if (DayPlan.RISK_CONNECTION_AT_RISK.equals(succ.riskStatus())) {
                planRepo.updateRiskStatus(succ.id(), DayPlan.RISK_NONE, now);
            }
            return;
        }
        List<RailConnection> inbound = connRepo.findActiveBySuccessor(succ.id());
        List<Evaluation> evals = inbound.stream().map(this::evaluate).toList();
        List<Evaluation> deficient = evals.stream().filter(e -> e.deficitMinutes() > 0).toList();
        if (deficient.isEmpty()) {
            if (DayPlan.RISK_CONNECTION_AT_RISK.equals(succ.riskStatus())) {
                planRepo.updateRiskStatus(succ.id(), DayPlan.RISK_NONE, now);
                for (Evaluation eval : evals) {
                    connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_RISK_CLEARED,
                            eval.conn().id(), eval.conn().predecessorPlanId(), succ.id(),
                            eval.pred().version(), eval.predArrivalMs(), eval.succDepartureMs(),
                            null, eval.conn().guaranteeMinutes(), eval.actualMinutes(),
                            eval.deficitMinutes(), null, null, now));
                }
            }
            return;
        }
        // 已开始计划不可自动标记为候选改签
        long departure = departureOf(planRepo.findOccupancies(succ.id()));
        if (!timeProvider.now().isBefore(Instant.ofEpochMilli(departure))) {
            return;
        }
        long[] window = computeWindow(succ, evals);
        for (Evaluation eval : deficient) {
            boolean unchanged = connRepo
                    .findLatestEvent(eval.conn().id(), ConnectionEvent.TYPE_RISK_MARKED)
                    .map(last -> last.predecessorVersion() == eval.pred().version()
                            && last.predArrivalUtc() == eval.predArrivalMs()
                            && last.succDepartureUtc() == eval.succDepartureMs()
                            && last.deficitMinutes() == eval.deficitMinutes())
                    .orElse(false);
            if (!unchanged) {
                connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_RISK_MARKED,
                        eval.conn().id(), eval.conn().predecessorPlanId(), succ.id(),
                        eval.pred().version(), eval.predArrivalMs(), eval.succDepartureMs(), null,
                        eval.conn().guaranteeMinutes(), eval.actualMinutes(), eval.deficitMinutes(),
                        window[0], window[1], now));
            }
        }
        if (!DayPlan.RISK_CONNECTION_AT_RISK.equals(succ.riskStatus())) {
            planRepo.updateRiskStatus(succ.id(), DayPlan.RISK_CONNECTION_AT_RISK, now);
        }
    }

    /**
     * 可选改签时间窗（后车新始发可行域，左闭右开）：
     * 起点为全部生效入向接续要求的最早始发；终点受后车运营日边界约束。
     */
    private long[] computeWindow(DayPlan succ, List<Evaluation> evals) {
        List<Occupancy> occs = planRepo.findOccupancies(succ.id());
        long firstStart = departureOf(occs);
        long lastEnd = arrivalOf(occs);
        long windowStart = Long.MIN_VALUE;
        for (Evaluation eval : evals) {
            windowStart = Math.max(windowStart,
                    eval.predArrivalMs() + eval.conn().guaranteeMinutes() * MINUTE_MILLIS);
        }
        long dayEnd = succ.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant()
                .toEpochMilli();
        // +1ms 使最迟可行始发（末占用恰抵运营日终点）落在左闭右开区间内
        long windowEnd = firstStart + (dayEnd - lastEnd) + 1;
        return new long[]{windowStart, windowEnd};
    }

    /**
     * 接续被新计划上的同对关系取代：取消并固化事件。
     */
    private void cancelSuperseded(RailConnection conn, long now) {
        connRepo.markCancelled(conn.id(), now);
        Evaluation eval = evaluate(conn);
        connRepo.insertEvent(new ConnectionEvent(0L, ConnectionEvent.TYPE_CANCELLED,
                conn.id(), conn.predecessorPlanId(), conn.successorPlanId(),
                eval.pred().version(), eval.predArrivalMs(), eval.succDepartureMs(), null,
                conn.guaranteeMinutes(), eval.actualMinutes(), eval.deficitMinutes(),
                null, null, now));
    }

    private void validateGuaranteeRange(int guaranteeMinutes) {
        if (guaranteeMinutes < MIN_GUARANTEE_MINUTES || guaranteeMinutes > MAX_GUARANTEE_MINUTES) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("guaranteeMinutes", guaranteeMinutes);
            detail.put("minMinutes", MIN_GUARANTEE_MINUTES);
            detail.put("maxMinutes", MAX_GUARANTEE_MINUTES);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_GUARANTEE_MINUTES",
                    "保障分钟数须在 " + MIN_GUARANTEE_MINUTES + "~" + MAX_GUARANTEE_MINUTES
                            + " 之间，实际: " + guaranteeMinutes, List.of(detail));
        }
    }

    private void validateRegistrable(DayPlan pred, DayPlan succ) {
        for (DayPlan plan : List.of(pred, succ)) {
            if (plan.status() == PlanStatus.CANCELLED) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_CANCELLED",
                        "计划已取消，不可登记接续: " + plan.scheduleKey());
            }
            if (plan.status() != PlanStatus.PUBLISHED) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_NOT_PUBLISHED",
                        "仅已发布计划可登记接续，计划 " + plan.scheduleKey() + " 当前状态: " + plan.status());
            }
        }
    }

    /**
     * 站点连续性：前车终到区段的到达站须等于后车始发区段的出发站。
     * 区段 ID 约定为 "出发站-到达站"（无分隔符时整体视为同一站点）。
     */
    private void validateStationContinuity(DayPlan pred, List<Occupancy> predOccs,
                                           DayPlan succ, List<Occupancy> succOccs) {
        Occupancy last = predOccs.stream()
                .max(java.util.Comparator.comparing(Occupancy::endUtc)
                        .thenComparing(Occupancy::seq))
                .orElseThrow();
        Occupancy first = succOccs.stream()
                .min(java.util.Comparator.comparing(Occupancy::startUtc)
                        .thenComparing(Occupancy::seq))
                .orElseThrow();
        String arrivalStation = destStation(last.sectionId());
        String originStation = originStation(first.sectionId());
        if (!arrivalStation.equals(originStation)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("predecessorScheduleKey", pred.scheduleKey());
            detail.put("successorScheduleKey", succ.scheduleKey());
            detail.put("predecessorLastSectionId", last.sectionId());
            detail.put("successorFirstSectionId", first.sectionId());
            detail.put("predecessorArrivalStation", arrivalStation);
            detail.put("successorOriginStation", originStation);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "STATION_NOT_CONTIGUOUS",
                    "站点不连续：前车终到站 " + arrivalStation + " 与后车始发站 " + originStation
                            + " 不一致", List.of(detail));
        }
    }

    private void validateOperationDays(DayPlan pred, DayPlan succ, boolean crossMidnight) {
        boolean sameDay = pred.opDate().equals(succ.opDate());
        boolean explicitCrossMidnight = crossMidnight
                && succ.opDate().equals(pred.opDate().plusDays(1));
        if (!sameDay && !explicitCrossMidnight) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("predecessorOpDate", pred.opDate().toString());
            detail.put("successorOpDate", succ.opDate().toString());
            detail.put("crossMidnight", crossMidnight);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OP_DATE_MISMATCH",
                    "接续计划须同运营日或明确跨零点：前车 " + pred.opDate() + "，后车 " + succ.opDate()
                            + "，crossMidnight=" + crossMidnight, List.of(detail));
        }
    }

    /**
     * 批量保障改签候选校验：已发布、处于 CONNECTION_AT_RISK 且未开始。
     */
    private void validateGuaranteeCandidate(DayPlan plan, List<Occupancy> occs) {
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw conflict("PLAN_STATE_CONFLICT",
                    "仅已发布计划可保障改签，计划 " + plan.scheduleKey() + " 当前状态: " + plan.status());
        }
        if (!DayPlan.RISK_CONNECTION_AT_RISK.equals(plan.riskStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_NOT_AT_RISK",
                    "计划未处于接续风险状态，不可保障改签: " + plan.scheduleKey());
        }
        long departure = departureOf(occs);
        if (!timeProvider.now().isBefore(Instant.ofEpochMilli(departure))) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("scheduleKey", plan.scheduleKey());
            detail.put("departureUtc", Instant.ofEpochMilli(departure).toString());
            detail.put("nowUtc", timeProvider.now().toString());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_ALREADY_STARTED",
                    "计划已开始，不可作为候选改签: " + plan.scheduleKey(), List.of(detail));
        }
    }

    /**
     * 计算单计划的最小可行平移：满足全部入向接续保障、全部出向接续保障与线路时隙。
     * 先处理计划的顺延结果对后续计划可见（同一事务的最终状态校验）。
     */
    private ShiftDecision computeShift(DayPlan plan, List<Occupancy> occs, Set<Long> batchIds,
                                       Map<Long, List<Occupancy>> shiftedByPlan,
                                       Map<Long, PublishedSlot> tentativeSlots,
                                       Map<Long, List<Occupancy>> occupanciesByPlan) {
        long departure = departureOf(occs);
        long lastEnd = arrivalOf(occs);
        long minShiftMs = 0;
        List<InboundEvaluation> inboundEvals = new ArrayList<>();
        for (RailConnection conn : connRepo.findActiveBySuccessor(plan.id())) {
            DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                    .orElseThrow(() -> new IllegalStateException("接续前车计划缺失: " + conn));
            List<Occupancy> predOccs = shiftedByPlan.getOrDefault(pred.id(),
                    occupanciesByPlan.getOrDefault(pred.id(), planRepo.findOccupancies(pred.id())));
            long predArrival = arrivalOf(predOccs);
            long required = predArrival + conn.guaranteeMinutes() * MINUTE_MILLIS;
            minShiftMs = Math.max(minShiftMs, required - departure);
            int predVersion = batchIds.contains(pred.id()) ? pred.version() + 1 : pred.version();
            long actual = minutesBetween(predArrival, departure);
            inboundEvals.add(new InboundEvaluation(conn, predVersion, predArrival, actual,
                    conn.guaranteeMinutes() - actual));
        }
        long dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant()
                .toEpochMilli();
        long maxShiftMs = dayEnd - lastEnd;
        // 出向接续：本计划作为前车延后后，仍须满足下游后车保障
        for (RailConnection conn : connRepo.findActiveByPredecessor(plan.id())) {
            List<Occupancy> succOccs = shiftedByPlan.getOrDefault(conn.successorPlanId(),
                    occupanciesByPlan.getOrDefault(conn.successorPlanId(),
                            planRepo.findOccupancies(conn.successorPlanId())));
            long succDeparture = departureOf(succOccs);
            maxShiftMs = Math.min(maxShiftMs,
                    succDeparture - conn.guaranteeMinutes() * MINUTE_MILLIS - lastEnd);
        }
        List<String> sectionIds = occs.stream().map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> slots = new ArrayList<>(planRepo.findPublishedSlots(plan.opDate(),
                sectionIds, batchIds));
        for (PublishedSlot tentative : tentativeSlots.values()) {
            slots.add(tentative);
        }
        long shiftMs = Math.max(minShiftMs, 0);
        while (true) {
            if (shiftMs > maxShiftMs) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("requiredShiftMinutes", Math.floorDiv(minShiftMs, MINUTE_MILLIS));
                detail.put("maxShiftMinutes", Math.floorDiv(maxShiftMs, MINUTE_MILLIS));
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_FEASIBLE_WINDOW",
                        "计划 " + plan.scheduleKey() + " 找不到不冲突时间窗，整次批量保障改签回滚",
                        List.of(detail));
            }
            long candidate = shiftMs;
            long nextShift = shiftMs;
            boolean conflicted = false;
            for (Occupancy o : occs) {
                long newStart = o.startUtc().toEpochMilli() + candidate;
                long newEnd = o.endUtc().toEpochMilli() + candidate;
                for (PublishedSlot slot : slots) {
                    if (!o.sectionId().equals(slot.sectionId())) {
                        continue;
                    }
                    boolean overlap = newStart < slot.endUtc().toEpochMilli()
                            && slot.startUtc().toEpochMilli() < newEnd;
                    if (overlap) {
                        conflicted = true;
                        nextShift = Math.max(nextShift,
                                slot.endUtc().toEpochMilli() - o.startUtc().toEpochMilli());
                    }
                }
            }
            if (!conflicted) {
                return new ShiftDecision(departure, departure + shiftMs, shiftMs, inboundEvals);
            }
            shiftMs = nextShift;
        }
    }

    private List<Occupancy> shiftOccupancies(List<Occupancy> occs, long shiftMs) {
        List<Occupancy> shifted = new ArrayList<>(occs.size());
        for (Occupancy o : occs) {
            shifted.add(new Occupancy(0L, o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                    o.startUtc().plusMillis(shiftMs), o.endUtc().plusMillis(shiftMs)));
        }
        return shifted;
    }

    private ConnectionView toView(RailConnection conn) {
        Evaluation eval = evaluate(conn);
        return new ConnectionView(conn.id(), eval.pred().scheduleKey(), eval.succ().scheduleKey(),
                conn.guaranteeMinutes(), conn.crossMidnight(), conn.status(),
                Instant.ofEpochMilli(eval.predArrivalMs()),
                Instant.ofEpochMilli(eval.succDepartureMs()),
                eval.actualMinutes(), eval.deficitMinutes(),
                Instant.ofEpochMilli(conn.createdAt()),
                conn.cancelledAt() == null ? null : Instant.ofEpochMilli(conn.cancelledAt()));
    }

    private ConnectionEventView toEventView(ConnectionEvent event, Map<Long, String> keyCache) {
        return new ConnectionEventView(event.id(), event.eventType(), event.connectionId(),
                scheduleKeyOf(event.predecessorPlanId(), keyCache),
                scheduleKeyOf(event.successorPlanId(), keyCache),
                event.predecessorVersion(), Instant.ofEpochMilli(event.predArrivalUtc()),
                Instant.ofEpochMilli(event.succDepartureUtc()),
                event.prevSuccDepartureUtc() == null ? null
                        : Instant.ofEpochMilli(event.prevSuccDepartureUtc()),
                event.guaranteeMinutes(), event.actualMinutes(), event.deficitMinutes(),
                event.windowStartUtc() == null ? null : Instant.ofEpochMilli(event.windowStartUtc()),
                event.windowEndUtc() == null ? null : Instant.ofEpochMilli(event.windowEndUtc()),
                Instant.ofEpochMilli(event.createdAt()));
    }

    private String scheduleKeyOf(long planId, Map<Long, String> keyCache) {
        return keyCache.computeIfAbsent(planId, id -> planRepo.findById(id)
                .orElseThrow(() -> new IllegalStateException("事件关联计划缺失: " + id))
                .scheduleKey());
    }

    /**
     * 前车终到时刻：占用最大 end（UTC 毫秒）。
     */
    private long arrivalOf(List<Occupancy> occs) {
        return occs.stream().mapToLong(o -> o.endUtc().toEpochMilli()).max().orElseThrow();
    }

    /**
     * 后车始发时刻：占用最小 start（UTC 毫秒）。
     */
    private long departureOf(List<Occupancy> occs) {
        return occs.stream().mapToLong(o -> o.startUtc().toEpochMilli()).min().orElseThrow();
    }

    private long minutesBetween(long fromMs, long toMs) {
        return Math.floorDiv(toMs - fromMs, MINUTE_MILLIS);
    }

    private String originStation(String sectionId) {
        int idx = sectionId.indexOf('-');
        return idx < 0 ? sectionId : sectionId.substring(0, idx);
    }

    private String destStation(String sectionId) {
        int idx = sectionId.lastIndexOf('-');
        return idx < 0 ? sectionId : sectionId.substring(idx + 1);
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

    private ApiException notFound(String scheduleKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
