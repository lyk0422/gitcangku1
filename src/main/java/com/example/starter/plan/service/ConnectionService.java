package com.example.starter.plan.service;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionDecision;
import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionRiskStatus;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RescheduleLink;
import com.example.starter.plan.repo.ConnectionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchGuaranteeItem;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.BatchGuaranteeResponse;
import com.example.starter.plan.web.dto.ConnectionDecisionView;
import com.example.starter.plan.web.dto.ConnectionDiagnosticsView;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路旅客接续保障：接续登记/取消、前车改签后的风险重算（同事务）、批量保障改签与查询。
 *
 * <p>裁决约定：接续写操作与发布/改签共用全局发布锁，按事务提交顺序串行；
 * (操作类型, requestKey) 幂等，同键同参重放首次结果、异参 409、失败不占键；
 * 风险与决策记录只插不改，后续取消接续或再次改签不改写历史证据。
 */
@Service
public class ConnectionService {

    /** 保障分钟数合法范围（含端点）。 */
    public static final int MIN_GUARANTEE_MINUTES = 1;
    public static final int MAX_GUARANTEE_MINUTES = 1440;

    private static final String OP_CONN_REGISTER = "CONN_REGISTER";
    private static final String OP_CONN_CANCEL = "CONN_CANCEL";
    private static final String OP_CONN_BATCH = "CONN_BATCH";

    private final PlanRepository planRepo;
    private final ConnectionRepository connRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlanService planService;
    private final TransactionTemplate tx;

    public ConnectionService(PlanRepository planRepo, ConnectionRepository connRepo,
                             IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                             Clock clock, @Lazy PlanService planService,
                             PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.connRepo = connRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.planService = planService;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------- 接续登记 / 取消 ----------

    /**
     * 登记接续：两趟已发布计划，后车始发不早于前车终到加保障分钟数。
     * 站点不连续、保障分钟数越界、计划已取消或未发布、运营日非同日和未明确跨零点均返回 422。
     */
    public ConnectionView register(RegisterConnectionRequest req) {
        if (req.guaranteeMinutes() < MIN_GUARANTEE_MINUTES
                || req.guaranteeMinutes() > MAX_GUARANTEE_MINUTES) {
            throw unprocessable("GUARANTEE_RANGE_INVALID",
                    "保障分钟数须在 [" + MIN_GUARANTEE_MINUTES + ", " + MAX_GUARANTEE_MINUTES + "] 内",
                    List.of(Map.of("type", "GUARANTEE_RANGE_INVALID",
                            "minMinutes", MIN_GUARANTEE_MINUTES,
                            "maxMinutes", MAX_GUARANTEE_MINUTES,
                            "actualMinutes", req.guaranteeMinutes())));
        }
        if (req.predecessorScheduleKey().equals(req.successorScheduleKey())) {
            throw badRequest("前车与后车不能是同一计划: " + req.predecessorScheduleKey());
        }
        String hash = sha256(OP_CONN_REGISTER + '\n' + req.predecessorScheduleKey() + '\n'
                + req.successorScheduleKey() + '\n' + req.transferSectionId() + '\n'
                + req.guaranteeMinutes() + '\n' + req.crossMidnight());
        Optional<ConnectionView> replay = replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash,
                ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan pred = planRepo.findByKeyForUpdate(req.predecessorScheduleKey())
                        .orElseThrow(() -> notFound(req.predecessorScheduleKey()));
                DayPlan succ = planRepo.findByKeyForUpdate(req.successorScheduleKey())
                        .orElseThrow(() -> notFound(req.successorScheduleKey()));
                requireRegisterable(pred, "前车");
                requireRegisterable(succ, "后车");
                validateOpDates(pred, succ, req.crossMidnight());
                List<Occupancy> predOccs = planRepo.findOccupancies(pred.id());
                List<Occupancy> succOccs = planRepo.findOccupancies(succ.id());
                validateStationContinuity(req.transferSectionId(), predOccs, succOccs);
                Instant predArrival = arrival(predOccs);
                Instant succDeparture = departure(succOccs);
                long actualGap = Duration.between(predArrival, succDeparture).toMinutes();
                if (actualGap < req.guaranteeMinutes()) {
                    throw guaranteeNotMet(req.guaranteeMinutes(), actualGap, predArrival,
                            succDeparture, null);
                }
                long now = clock.millis();
                long connId = connRepo.insertConnection(pred.id(), succ.id(), req.transferSectionId(),
                        req.guaranteeMinutes(), pred.version(), req.crossMidnight(), now);
                ConnectionView view = toConnectionView(connRepo.findById(connId)
                        .orElseThrow(() -> new IllegalStateException("接续登记缺失: " + connId)));
                idemRepo.insert(OP_CONN_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_EXISTS",
                            "同一对前后车已存在接续登记: " + req.predecessorScheduleKey()
                                    + " -> " + req.successorScheduleKey()));
        }
    }

    /**
     * 取消接续登记：仅状态翻转，历史风险与决策记录不改写；取消后重算后车风险标记。
     */
    public ConnectionView cancel(long connectionId, String requestKey) {
        String hash = sha256(OP_CONN_CANCEL + '\n' + connectionId);
        Optional<ConnectionView> replay = replayIfPresent(OP_CONN_CANCEL, requestKey, hash,
                ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Connection conn = findConnectionForUpdate(connectionId);
                if (conn.status() != ConnectionStatus.ACTIVE) {
                    throw conflict("CONNECTION_STATE_CONFLICT",
                            "接续登记已取消，不可重复取消: " + connectionId);
                }
                long now = clock.millis();
                connRepo.cancelConnection(connectionId, now);
                // 取消后重算后车（当前生效计划）风险标记，历史风险记录不改写
                recomputeRiskFlag(resolveTip(conn.successorPlanId()).id(), now);
                ConnectionView view = toConnectionView(connRepo.findById(connectionId)
                        .orElseThrow(() -> new IllegalStateException("接续登记缺失: " + connectionId)));
                idemRepo.insert(OP_CONN_CANCEL, requestKey, hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_STATE_CONFLICT", "接续登记状态冲突"));
        }
    }

    // ---------- 风险重算（由 PlanService.reschedule 在同一事务内调用） ----------

    /**
     * 前车改签后重算其全部直接接续：保障不足的后车标记 AT_RISK 并追加不可变风险记录
     * （含可选改签时间窗）；保障恢复的后车清除标记。不自动修改后车计划。
     * 判定时刻取业务时钟（测试可替换），与改签同事务提交。
     *
     * @param oldPred 改签中被取消的前车旧计划
     * @param newPred 改签后发布的前车新计划
     */
    public void reevaluateAfterPredecessorReschedule(DayPlan oldPred, DayPlan newPred) {
        long nowMillis = clock.millis();
        List<Connection> connections = new ArrayList<>();
        for (Long predId : selfAndAncestorIds(oldPred.id())) {
            connections.addAll(connRepo.findActiveByPredecessor(predId));
        }
        if (connections.isEmpty()) {
            return;
        }
        Instant oldPredArrival = arrival(planRepo.findOccupancies(oldPred.id()));
        List<Occupancy> newPredOccs = planRepo.findOccupancies(newPred.id());
        Instant newPredArrival = arrival(newPredOccs);
        Set<Long> evaluatedSuccessors = new LinkedHashSet<>();
        for (Connection conn : connections) {
            DayPlan succ = resolveTip(conn.successorPlanId());
            if (succ.status() != PlanStatus.PUBLISHED) {
                continue;
            }
            evaluatedSuccessors.add(succ.id());
            List<Occupancy> succOccs = planRepo.findOccupancies(succ.id());
            Instant succDeparture = departure(succOccs);
            long actualGap = Duration.between(newPredArrival, succDeparture).toMinutes();
            if (actualGap >= conn.guaranteeMinutes()) {
                continue;
            }
            boolean transferServed = newPredOccs.stream()
                    .anyMatch(o -> o.sectionId().equals(conn.transferSectionId()));
            boolean started = !Instant.ofEpochMilli(nowMillis).isBefore(succDeparture);
            // 已开始计划不可自动标记为候选改签；换乘站不再经停同样无候选窗
            Instant[] window = null;
            if (transferServed && !started) {
                window = findCandidateWindow(succ, succOccs,
                        newPredArrival.plusMillis(conn.guaranteeMinutes() * 60_000L));
            }
            connRepo.insertRisk(new ConnectionRisk(0L, conn.id(), newPred.id(), succ.id(),
                    oldPred.id(), newPred.id(), oldPred.version(), newPred.version(),
                    oldPredArrival, newPredArrival, succDeparture,
                    window == null ? null : window[0],
                    conn.guaranteeMinutes(), conn.guaranteeMinutes(), actualGap,
                    conn.guaranteeMinutes() - actualGap,
                    window == null ? null : window[0],
                    window == null ? null : window[1],
                    window != null, started, nowMillis));
        }
        for (Long succId : evaluatedSuccessors) {
            recomputeRiskFlag(succId, nowMillis);
        }
    }

    // ---------- 批量保障改签 ----------

    /**
     * 批量保障改签：对选定的一组 AT_RISK 后车逐条执行原子改签（复用全局发布锁与行锁，
     * 全部条目共享同一事务）。任一条目找不到不冲突时间窗或不满足全部接续分钟数时
     * 整次 422，已发布计划时刻、风险标记与时隙占用均回滚。
     */
    public BatchGuaranteeResponse batchGuarantee(BatchGuaranteeRequest req) {
        Set<String> seen = new LinkedHashSet<>();
        for (BatchGuaranteeItem item : req.items()) {
            if (!seen.add(item.successorScheduleKey())) {
                throw badRequest("批量条目后车重复: " + item.successorScheduleKey());
            }
            if (item.successorScheduleKey().equals(item.newScheduleKey())) {
                throw badRequest("新旧计划必须不同: " + item.successorScheduleKey());
            }
        }
        String hash = hashBatch(req);
        Optional<BatchGuaranteeResponse> replay = replayIfPresent(OP_CONN_BATCH, req.requestKey(),
                hash, BatchGuaranteeResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                List<ConnectionDecision> decisions = new ArrayList<>();
                List<ConnectionDecisionView> views = new ArrayList<>();
                for (BatchGuaranteeItem item : req.items()) {
                    DayPlan oldSucc = planRepo.findByKeyForUpdate(item.successorScheduleKey())
                            .orElseThrow(() -> notFound(item.successorScheduleKey()));
                    if (oldSucc.status() != PlanStatus.PUBLISHED
                            || oldSucc.connectionRisk() != ConnectionRiskStatus.AT_RISK) {
                        throw unprocessable("NOT_AT_RISK",
                                "后车不是可保障改签的风险计划: " + item.successorScheduleKey(),
                                List.of(detail("type", "NOT_AT_RISK",
                                        "scheduleKey", item.successorScheduleKey(),
                                        "status", oldSucc.status().name(),
                                        "connectionRisk", oldSucc.connectionRisk().name())));
                    }
                    DayPlan newPlan = planRepo.findByKeyForUpdate(item.newScheduleKey())
                            .orElseThrow(() -> notFound(item.newScheduleKey()));
                    List<Occupancy> newOccs = planRepo.findOccupancies(newPlan.id());
                    Instant newDeparture = departure(newOccs);
                    Instant newArrival = arrival(newOccs);
                    List<Connection> incoming = activeConnectionsForSuccessorTip(oldSucc.id());
                    // 全部接续分钟数（后车侧）：新车始发须满足每条生效接续
                    for (Connection conn : incoming) {
                        DayPlan predTip = resolveTip(conn.predecessorPlanId());
                        if (predTip.status() != PlanStatus.PUBLISHED) {
                            throw unprocessable("PREDECESSOR_NOT_PUBLISHED",
                                    "接续前车当前未发布，无法保障改签: " + conn.id(),
                                    List.of(detail("type", "PREDECESSOR_NOT_PUBLISHED",
                                            "connectionId", conn.id(),
                                            "predecessorScheduleKey", predTip.scheduleKey(),
                                            "status", predTip.status().name())));
                        }
                        List<Occupancy> predOccs = planRepo.findOccupancies(predTip.id());
                        boolean served = predOccs.stream()
                                .anyMatch(o -> o.sectionId().equals(conn.transferSectionId()));
                        if (!served) {
                            throw unprocessable("STATION_NOT_CONTINUOUS",
                                    "前车当前计划不再经停换乘站: " + conn.transferSectionId(),
                                    List.of(detail("type", "STATION_NOT_CONTINUOUS",
                                            "connectionId", conn.id(),
                                            "transferSectionId", conn.transferSectionId())));
                        }
                        Instant predArrival = arrival(predOccs);
                        long gap = Duration.between(predArrival, newDeparture).toMinutes();
                        if (gap < conn.guaranteeMinutes()) {
                            throw guaranteeNotMet(conn.guaranteeMinutes(), gap, predArrival,
                                    newDeparture, conn.id());
                        }
                    }
                    // 全部接续分钟数（前车侧）：该车作为前车的接续不得被新终到破坏
                    for (Connection conn : activeConnectionsForPredecessorTip(oldSucc.id())) {
                        DayPlan succTip = resolveTip(conn.successorPlanId());
                        if (succTip.status() != PlanStatus.PUBLISHED) {
                            continue;
                        }
                        Instant succDep = departure(planRepo.findOccupancies(succTip.id()));
                        long gap = Duration.between(newArrival, succDep).toMinutes();
                        if (gap < conn.guaranteeMinutes()) {
                            throw guaranteeNotMet(conn.guaranteeMinutes(), gap, newArrival,
                                    succDep, conn.id());
                        }
                    }
                    // 原子改签（加入当前事务；内部触发该车作为前车时的接续风险重算）
                    planService.reschedule(item.successorScheduleKey(),
                            new RescheduleRequest(innerRequestKey(req.requestKey(),
                                    item.successorScheduleKey()), item.newScheduleKey(),
                                    item.expectedOldVersion(), item.expectedNewVersion()));
                    long now = clock.millis();
                    Instant oldDeparture = departure(planRepo.findOccupancies(oldSucc.id()));
                    if (incoming.isEmpty()) {
                        ConnectionDecision d = new ConnectionDecision(0L, req.requestKey(),
                                newPlan.id(), oldSucc.id(), newPlan.id(), null, null,
                                oldSucc.version(), oldDeparture, newDeparture, null,
                                null, null, null, null, "REBOOKED", null, now);
                        decisions.add(d);
                        views.add(toDecisionView(d));
                    } else {
                        for (Connection conn : incoming) {
                            DayPlan predTip = resolveTip(conn.predecessorPlanId());
                            Instant predArrival = arrival(planRepo.findOccupancies(predTip.id()));
                            long gap = Duration.between(predArrival, newDeparture).toMinutes();
                            ConnectionDecision d = new ConnectionDecision(0L, req.requestKey(),
                                    newPlan.id(), oldSucc.id(), newPlan.id(), conn.id(),
                                    predTip.id(), oldSucc.version(), oldDeparture, newDeparture,
                                    predArrival, conn.guaranteeMinutes(), conn.guaranteeMinutes(),
                                    gap, conn.guaranteeMinutes() - gap, "REBOOKED", null, now);
                            decisions.add(d);
                            views.add(toDecisionView(d));
                        }
                    }
                    recomputeRiskFlag(newPlan.id(), now);
                }
                for (ConnectionDecision d : decisions) {
                    connRepo.insertDecision(d);
                }
                BatchGuaranteeResponse response = new BatchGuaranteeResponse(req.requestKey(), views);
                idemRepo.insert(OP_CONN_BATCH, req.requestKey(), hash, toJson(response),
                        clock.millis());
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_BATCH, req.requestKey(), hash, BatchGuaranteeResponse.class)
                    .orElseThrow(() -> conflict("LINK_CONFLICT", "批量保障改签关联冲突"));
        }
    }

    // ---------- 查询（只读，不改变状态） ----------

    /**
     * 接续登记明细（含基于当前生效计划的实时评估），不存在返回 404。
     */
    public ConnectionView getConnection(long connectionId) {
        Connection conn = connRepo.findById(connectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND",
                        "接续登记不存在: " + connectionId));
        return toConnectionView(conn);
    }

    /**
     * 指定计划（任一侧）的全部接续登记，按登记顺序。
     */
    public List<ConnectionView> getConnectionsForPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findByPlan(plan.id()).stream().map(this::toConnectionView).toList();
    }

    /**
     * 指定计划作为后车被标记的全部风险记录，按生成顺序（不可变历史）。
     */
    public List<ConnectionRiskView> getRisksForPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findRisksBySuccessor(plan.id()).stream().map(this::toRiskView).toList();
    }

    /**
     * 指定计划相关的全部批量保障改签决策记录，按生成顺序（不可变历史）。
     */
    public List<ConnectionDecisionView> getDecisionsForPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey).orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findDecisionsBySuccessorPlan(plan.id()).stream()
                .map(this::toDecisionView).toList();
    }

    /**
     * 接续诊断：计划快照、关联接续、风险与决策历史汇总。只读。
     */
    public ConnectionDiagnosticsView getDiagnostics(String scheduleKey) {
        PlanResponse plan = planService.getPlan(scheduleKey);
        return new ConnectionDiagnosticsView(plan, getConnectionsForPlan(scheduleKey),
                getRisksForPlan(scheduleKey), getDecisionsForPlan(scheduleKey));
    }

    // ---------- 内部实现 ----------

    private Connection findConnectionForUpdate(long connectionId) {
        // 接续行更新与计划写共用发布锁串行，无需行锁；此处按 id 读取
        return connRepo.findById(connectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONNECTION_NOT_FOUND",
                        "接续登记不存在: " + connectionId));
    }

    private void requireRegisterable(DayPlan plan, String role) {
        if (plan.status() == PlanStatus.CANCELLED) {
            throw unprocessable("PLAN_CANCELLED", role + "计划已取消，不可登记接续",
                    List.of(detail("type", "PLAN_CANCELLED", "scheduleKey", plan.scheduleKey(),
                            "status", plan.status().name())));
        }
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw unprocessable("PLAN_NOT_PUBLISHED", role + "计划未发布，不可登记接续",
                    List.of(detail("type", "PLAN_NOT_PUBLISHED", "scheduleKey", plan.scheduleKey(),
                            "status", plan.status().name())));
        }
    }

    private void validateOpDates(DayPlan pred, DayPlan succ, boolean crossMidnight) {
        boolean sameDay = pred.opDate().equals(succ.opDate());
        boolean nextDay = succ.opDate().equals(pred.opDate().plusDays(1));
        if ((sameDay && !crossMidnight) || (nextDay && crossMidnight)) {
            return;
        }
        throw unprocessable("OP_DATE_INVALID",
                "接续计划必须同运营日或明确跨零点: 前车=" + pred.opDate() + " 后车=" + succ.opDate(),
                List.of(detail("type", "OP_DATE_INVALID",
                        "predecessorOpDate", pred.opDate().toString(),
                        "successorOpDate", succ.opDate().toString(),
                        "crossMidnight", String.valueOf(crossMidnight))));
    }

    private void validateStationContinuity(String transferSectionId, List<Occupancy> predOccs,
                                           List<Occupancy> succOccs) {
        String predLast = predOccs.stream().max(Comparator.comparingInt(Occupancy::seq))
                .orElseThrow().sectionId();
        String succFirst = succOccs.stream().min(Comparator.comparingInt(Occupancy::seq))
                .orElseThrow().sectionId();
        if (!predLast.equals(transferSectionId) || !succFirst.equals(transferSectionId)) {
            throw unprocessable("STATION_NOT_CONTINUOUS",
                    "换乘站须同时为前车终到与后车始发站点: " + transferSectionId,
                    List.of(detail("type", "STATION_NOT_CONTINUOUS",
                            "transferSectionId", transferSectionId,
                            "predecessorLastSectionId", predLast,
                            "successorFirstSectionId", succFirst)));
        }
    }

    private ApiException guaranteeNotMet(int required, long actual, Instant predArrival,
                                         Instant succDeparture, Long connectionId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", "GUARANTEE_NOT_MET");
        if (connectionId != null) {
            d.put("connectionId", connectionId);
        }
        d.put("requiredGapMinutes", required);
        d.put("actualGapMinutes", actual);
        d.put("shortfallMinutes", required - actual);
        d.put("predecessorArrivalUtc", predArrival.toString());
        d.put("successorDepartureUtc", succDeparture.toString());
        return unprocessable("GUARANTEE_NOT_MET",
                "接续保障不足：要求 " + required + " 分钟，实际 " + actual + " 分钟", List.of(d));
    }

    /**
     * 计算后车可选改签时间窗：新车始发 d ∈ [earliest, 运营日容纳上限] 且平移后
     * 全部占用不与其他已发布计划冲突的第一个连续可行区间（左闭右开）；无可行区间返回 null。
     */
    private Instant[] findCandidateWindow(DayPlan succ, List<Occupancy> occs, Instant earliest) {
        Instant oldDeparture = departure(occs);
        Instant lastEnd = arrival(occs);
        Instant dayEnd = succ.opDate().plusDays(1)
                .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
        // 平移后最晚占用结束不得越出运营日：d <= dayEnd - (lastEnd - oldDeparture)
        long limit = dayEnd.toEpochMilli() - (lastEnd.toEpochMilli() - oldDeparture.toEpochMilli());
        List<String> sections = occs.stream().map(Occupancy::sectionId).distinct().toList();
        List<PublishedSlot> slots = planRepo.findPublishedSlots(succ.opDate(), sections,
                List.of(succ.id()));
        List<long[]> forbidden = new ArrayList<>();
        for (Occupancy o : occs) {
            for (PublishedSlot s : slots) {
                if (!o.sectionId().equals(s.sectionId())) {
                    continue;
                }
                // d 使 [o.start+δ, o.end+δ) 与 [s.start, s.end) 相交的禁用开区间
                long fs = s.startUtc().toEpochMilli() - o.endUtc().toEpochMilli()
                        + oldDeparture.toEpochMilli();
                long fe = s.endUtc().toEpochMilli() - o.startUtc().toEpochMilli()
                        + oldDeparture.toEpochMilli();
                forbidden.add(new long[]{fs, fe});
            }
        }
        forbidden.sort(Comparator.comparingLong(a -> a[0]));
        long cursor = earliest.toEpochMilli();
        for (long[] f : forbidden) {
            if (f[1] <= cursor) {
                continue;
            }
            if (f[0] > cursor && cursor <= limit) {
                return new Instant[]{Instant.ofEpochMilli(cursor),
                        Instant.ofEpochMilli(Math.min(f[0], limit + 1))};
            }
            cursor = Math.max(cursor, f[1]);
        }
        if (cursor <= limit) {
            return new Instant[]{Instant.ofEpochMilli(cursor), Instant.ofEpochMilli(limit + 1)};
        }
        return null;
    }

    /**
     * 重算后车（当前生效计划）的接续风险标记：任一生效接续保障不足为 AT_RISK，否则 NONE。
     */
    private void recomputeRiskFlag(long successorTipPlanId, long nowMillis) {
        DayPlan succ = planRepo.findById(successorTipPlanId)
                .orElseThrow(() -> new IllegalStateException("计划缺失: " + successorTipPlanId));
        boolean atRisk = false;
        if (succ.status() == PlanStatus.PUBLISHED) {
            Instant succDeparture = departure(planRepo.findOccupancies(succ.id()));
            for (Connection conn : activeConnectionsForSuccessorTip(succ.id())) {
                DayPlan predTip = resolveTip(conn.predecessorPlanId());
                if (predTip.status() != PlanStatus.PUBLISHED) {
                    continue;
                }
                Instant predArrival = arrival(planRepo.findOccupancies(predTip.id()));
                if (Duration.between(predArrival, succDeparture).toMinutes()
                        < conn.guaranteeMinutes()) {
                    atRisk = true;
                    break;
                }
            }
        }
        planRepo.updateConnectionRisk(succ.id(),
                atRisk ? ConnectionRiskStatus.AT_RISK : ConnectionRiskStatus.NONE, nowMillis);
    }

    /**
     * 查询以后车当前生效计划（含其改签链祖先登记）关联的全部生效接续。
     */
    private List<Connection> activeConnectionsForSuccessorTip(long successorTipPlanId) {
        List<Connection> result = new ArrayList<>();
        for (Long id : selfAndAncestorIds(successorTipPlanId)) {
            result.addAll(connRepo.findActiveBySuccessor(id));
        }
        return result;
    }

    /**
     * 查询以前车当前生效计划（含其改签链祖先登记）关联的全部生效接续。
     */
    private List<Connection> activeConnectionsForPredecessorTip(long predecessorTipPlanId) {
        List<Connection> result = new ArrayList<>();
        for (Long id : selfAndAncestorIds(predecessorTipPlanId)) {
            result.addAll(connRepo.findActiveByPredecessor(id));
        }
        return result;
    }

    /**
     * 沿改签链解析当前生效计划（链头）。
     */
    private DayPlan resolveTip(long planId) {
        DayPlan cur = planRepo.findById(planId)
                .orElseThrow(() -> new IllegalStateException("计划缺失: " + planId));
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkByPredecessor(cur.id());
            if (link.isEmpty()) {
                return cur;
            }
            cur = planRepo.findById(link.get().successorPlanId())
                    .orElseThrow(() -> new IllegalStateException("改签链后继缺失: " + link.get()));
        }
    }

    /**
     * 自身及改签链全部祖先计划 id（沿前驱方向）。
     */
    private List<Long> selfAndAncestorIds(long planId) {
        List<Long> ids = new ArrayList<>();
        long cur = planId;
        ids.add(cur);
        while (true) {
            Optional<RescheduleLink> link = planRepo.findLinkBySuccessor(cur);
            if (link.isEmpty()) {
                return ids;
            }
            cur = link.get().predecessorPlanId();
            ids.add(cur);
        }
    }

    private ConnectionView toConnectionView(Connection conn) {
        DayPlan pred = resolveTip(conn.predecessorPlanId());
        DayPlan succ = resolveTip(conn.successorPlanId());
        Instant predArrival = arrival(planRepo.findOccupancies(pred.id()));
        Instant succDeparture = departure(planRepo.findOccupancies(succ.id()));
        long actualGap = Duration.between(predArrival, succDeparture).toMinutes();
        return new ConnectionView(conn.id(), planKeyOf(conn.predecessorPlanId()),
                planKeyOf(conn.successorPlanId()), conn.transferSectionId(), conn.guaranteeMinutes(),
                conn.crossMidnight(), conn.status().name(), predArrival, succDeparture,
                conn.guaranteeMinutes(), actualGap, conn.guaranteeMinutes() - actualGap,
                Instant.ofEpochMilli(conn.createdAt()),
                conn.cancelledAt() == null ? null : Instant.ofEpochMilli(conn.cancelledAt()));
    }

    private ConnectionRiskView toRiskView(ConnectionRisk r) {
        return new ConnectionRiskView(r.id(), r.connectionId(), planKeyOf(r.predecessorPlanId()),
                planKeyOf(r.successorPlanId()), planKeyOf(r.predecessorOldPlanId()),
                planKeyOf(r.predecessorNewPlanId()), r.predecessorBaseVersion(),
                r.predecessorRescheduledVersion(), r.oldPredArrivalUtc(), r.newPredArrivalUtc(),
                r.oldSuccessorDepartureUtc(), r.newSuccessorDepartureUtc(), r.guaranteeMinutes(),
                r.requiredGapMinutes(), r.actualGapMinutes(), r.shortfallMinutes(),
                r.candidateWindowStartUtc(), r.candidateWindowEndUtc(), r.candidateAvailable(),
                r.successorStarted(), Instant.ofEpochMilli(r.createdAt()));
    }

    private ConnectionDecisionView toDecisionView(ConnectionDecision d) {
        return new ConnectionDecisionView(d.id(), d.batchRequestKey(),
                d.successorPlanId() == null ? null : planKeyOf(d.successorPlanId()),
                d.oldSuccessorPlanId() == null ? null : planKeyOf(d.oldSuccessorPlanId()),
                d.newSuccessorPlanId() == null ? null : planKeyOf(d.newSuccessorPlanId()),
                d.connectionId(),
                d.predecessorPlanId() == null ? null : planKeyOf(d.predecessorPlanId()),
                d.successorBaseVersion(), d.oldDepartureUtc(), d.newDepartureUtc(),
                d.predecessorArrivalUtc(), d.guaranteeMinutes(), d.requiredGapMinutes(),
                d.actualGapMinutes(), d.shortfallMinutes(), d.itemResult(), d.reasonCode(),
                Instant.ofEpochMilli(d.createdAt()));
    }

    private String planKeyOf(long planId) {
        return planRepo.findById(planId)
                .orElseThrow(() -> new IllegalStateException("计划缺失: " + planId))
                .scheduleKey();
    }

    private static Instant arrival(List<Occupancy> occs) {
        return occs.stream().map(Occupancy::endUtc).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static Instant departure(List<Occupancy> occs) {
        return occs.stream().map(Occupancy::startUtc).min(Comparator.naturalOrder()).orElseThrow();
    }

    private String innerRequestKey(String batchRequestKey, String successorScheduleKey) {
        return "B#" + sha256(batchRequestKey + "#" + successorScheduleKey);
    }

    private String hashBatch(BatchGuaranteeRequest req) {
        StringBuilder sb = new StringBuilder(OP_CONN_BATCH);
        for (BatchGuaranteeItem item : req.items()) {
            sb.append('\n').append(item.successorScheduleKey()).append('|')
                    .append(item.newScheduleKey()).append('|')
                    .append(item.expectedOldVersion()).append('|').append(item.expectedNewVersion());
        }
        return sha256(sb.toString());
    }

    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private Map<String, Object> detail(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
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
        return new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + scheduleKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException unprocessable(String code, String message,
                                       List<Map<String, Object>> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}
