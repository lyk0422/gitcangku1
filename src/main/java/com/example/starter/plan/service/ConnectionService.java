package com.example.starter.plan.service;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.GuaranteeDecision;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.repo.ConnectionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchGuaranteeRequest;
import com.example.starter.plan.web.dto.BatchGuaranteeResponse;
import com.example.starter.plan.web.dto.ConnectionRegisterRequest;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.GuaranteeDecisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 铁路接续保障核心业务：接续登记/取消、前车改签后的风险重算、批量保障改签与历史查询。
 *
 * <p>并发与幂等约定与日计划一致：写操作按 (操作类型, requestKey) 幂等，同键同参重放返回首次
 * 成功结果，同键不同参 409，失败不占键；接续登记、取消与批量保障改签均先获取全局发布锁，
 * 再按计划行锁提交顺序裁决，同一后车并发改签只形成一个生效时刻。
 *
 * <p>历史语义：接续风险记录与保障改签决策记录为追加式历史，固化判定/决策所依据的前车版本、
 * 原新时刻与保障差额；后续取消接续或再次改签不改写历史记录。
 */
@Service
public class ConnectionService {

    /** 保障分钟数合法下限（含）。 */
    public static final int MIN_GUARANTEE_MINUTES = 5;
    /** 保障分钟数合法上限（含）。 */
    public static final int MAX_GUARANTEE_MINUTES = 720;

    private static final String OP_CONN_REGISTER = "CONN_REGISTER";
    private static final String OP_CONN_CANCEL = "CONN_CANCEL";
    private static final String OP_BATCH_GUARANTEE = "BATCH_GUARANTEE";

    private final PlanRepository planRepo;
    private final ConnectionRepository connRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ConnectionService(PlanRepository planRepo, ConnectionRepository connRepo,
                             IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                             PlatformTransactionManager txManager, Clock clock) {
        this.planRepo = planRepo;
        this.connRepo = connRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 登记接续关系：两趟已发布计划间，后车始发必须不早于前车终到加保障分钟数。
     * 站点不连续、保障分钟数越界、计划已取消、运营日非同日且未明确跨零点、
     * 接续时间不足均返回 422；重复登记返回 409。
     */
    public ConnectionView register(ConnectionRegisterRequest req) {
        boolean crossMidnight = Boolean.TRUE.equals(req.crossMidnight());
        int guarantee = req.guaranteeMinutes();
        if (guarantee < MIN_GUARANTEE_MINUTES || guarantee > MAX_GUARANTEE_MINUTES) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "GUARANTEE_MINUTES_INVALID");
            detail.put("actualMinutes", guarantee);
            detail.put("minMinutes", MIN_GUARANTEE_MINUTES);
            detail.put("maxMinutes", MAX_GUARANTEE_MINUTES);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GUARANTEE_MINUTES_INVALID",
                    "保障分钟数须在 " + MIN_GUARANTEE_MINUTES + "~" + MAX_GUARANTEE_MINUTES
                            + " 之间，实际: " + guarantee, List.of(detail));
        }
        if (req.predecessorKey().equals(req.successorKey())) {
            throw badRequest("前后车计划必须不同: " + req.predecessorKey());
        }
        String hash = hashRegister(req, crossMidnight);
        Optional<ConnectionView> replay =
                replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                DayPlan pred = planRepo.findByKeyForUpdate(req.predecessorKey())
                        .orElseThrow(() -> notFound(req.predecessorKey()));
                DayPlan succ = planRepo.findByKeyForUpdate(req.successorKey())
                        .orElseThrow(() -> notFound(req.successorKey()));
                if (pred.status() == PlanStatus.CANCELLED || succ.status() == PlanStatus.CANCELLED) {
                    throw unprocessable("CONNECTION_PLAN_CANCELLED",
                            "已取消计划不可登记接续: " + pred.scheduleKey() + " / " + succ.scheduleKey());
                }
                if (pred.status() != PlanStatus.PUBLISHED || succ.status() != PlanStatus.PUBLISHED) {
                    throw conflict("PLAN_STATE_CONFLICT",
                            "仅已发布计划可登记接续，当前状态: 前车=" + pred.status() + " 后车=" + succ.status());
                }
                validateOperationDates(pred, succ, crossMidnight);
                List<Occupancy> predOcc = planRepo.findOccupancies(pred.id());
                List<Occupancy> succOcc = planRepo.findOccupancies(succ.id());
                Occupancy predLast = lastOccupancy(predOcc);
                Occupancy succFirst = firstOccupancy(succOcc);
                validateStationContinuity(req.transferStation(), predLast, succFirst);
                validateGap(predLast.endUtc(), succFirst.startUtc(), guarantee);
                if (connRepo.findActiveByPair(pred.id(), succ.id()).isPresent()) {
                    throw conflict("CONNECTION_EXISTS",
                            "前后车间已存在生效接续: " + pred.scheduleKey() + " -> " + succ.scheduleKey());
                }
                long now = System.currentTimeMillis();
                long id = connRepo.insertConnection(pred.id(), succ.id(), req.transferStation(),
                        guarantee, crossMidnight, pred.version(), now);
                ConnectionView view = new ConnectionView(id, pred.scheduleKey(), succ.scheduleKey(),
                        req.transferStation(), guarantee, crossMidnight, pred.version(),
                        ConnectionStatus.ACTIVE.name());
                idemRepo.insert(OP_CONN_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_EXISTS", "前后车间已存在生效接续"));
        }
    }

    /**
     * 取消接续：状态置 CANCELLED，历史风险记录不改写；后车风险标记按剩余生效接续重估。
     */
    public ConnectionView cancelConnection(long connectionId, String requestKey) {
        String hash = sha256(OP_CONN_CANCEL + '\n' + connectionId);
        Optional<ConnectionView> replay =
                replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Connection conn = connRepo.findByIdForUpdate(connectionId)
                        .orElseThrow(() -> connectionNotFound(connectionId));
                if (conn.status() != ConnectionStatus.ACTIVE) {
                    throw conflict("CONNECTION_STATE_CONFLICT",
                            "仅生效接续可取消，当前状态: " + conn.status());
                }
                long now = System.currentTimeMillis();
                connRepo.cancel(connectionId, now);
                refreshSuccessorRiskFlag(conn.successorPlanId());
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
     * 接续明细查询，不存在返回 404；读取不改变状态。
     */
    public ConnectionView getConnection(long connectionId) {
        Connection conn = connRepo.findById(connectionId)
                .orElseThrow(() -> connectionNotFound(connectionId));
        return toView(conn);
    }

    /**
     * 查询计划参与的全部接续（作为前车或后车，含已取消历史），按登记顺序。
     */
    public List<ConnectionView> listConnections(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findByPlan(plan.id()).stream().map(this::toView).toList();
    }

    /**
     * 查询后车全部接续风险历史（追加式，不改写），按生成顺序。
     */
    public List<ConnectionRiskView> listRisks(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findRisksBySuccessor(plan.id()).stream()
                .map(r -> new ConnectionRiskView(r.id(), r.connectionId(),
                        scheduleKeyOf(r.predecessorPlanId()), r.predecessorVersion(),
                        scheduleKeyOf(r.successorPlanId()),
                        r.predecessorOldArrivalUtc(), r.predecessorNewArrivalUtc(),
                        r.requiredMinutes(), r.actualMinutes(), r.deficitMinutes(),
                        r.windowStartUtc(), r.windowEndUtc()))
                .toList();
    }

    /**
     * 查询后车全部保障改签决策历史（追加式，不改写），按决策顺序。
     */
    public List<GuaranteeDecisionView> listDecisions(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findDecisionsBySuccessor(plan.id()).stream()
                .map(d -> new GuaranteeDecisionView(d.batchKey(), scheduleKeyOf(d.successorPlanId()),
                        d.successorVersion(), d.predecessorVersion(),
                        d.oldFirstStartUtc(), d.newFirstStartUtc(),
                        d.shiftMinutes(), d.deficitMinutes()))
                .toList();
    }

    /**
     * 批量保障改签：对选定的一组风险后车统一寻找同时满足线路时隙与全部接续分钟数的
     * 时间窗并整体后移。先校验完整最终状态再事务提交；任一计划找不到不冲突时间窗时
     * 整次 422，已发布计划时刻、风险标记与时隙占用均不变。
     */
    public BatchGuaranteeResponse batchGuarantee(BatchGuaranteeRequest req) {
        if (new HashSet<>(req.scheduleKeys()).size() != req.scheduleKeys().size()) {
            throw badRequest("scheduleKeys 不得重复");
        }
        String hash = hashBatch(req);
        Optional<BatchGuaranteeResponse> replay =
                replayIfPresent(OP_BATCH_GUARANTEE, req.requestKey(), hash, BatchGuaranteeResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                List<DayPlan> plans = new ArrayList<>();
                for (String key : req.scheduleKeys()) {
                    plans.add(planRepo.findByKeyForUpdate(key).orElseThrow(() -> notFound(key)));
                }
                Instant now = Instant.now(clock);
                Map<Long, List<Occupancy>> occupancies = new HashMap<>();
                for (DayPlan plan : plans) {
                    if (plan.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅已发布计划可保障改签，当前状态: " + plan.scheduleKey()
                                        + " " + plan.status());
                    }
                    if (!DayPlan.RISK_CONNECTION_AT_RISK.equals(plan.connectionRisk())) {
                        throw unprocessable("PLAN_NOT_AT_RISK",
                                "计划未处于接续风险状态: " + plan.scheduleKey());
                    }
                    List<Occupancy> occ = planRepo.findOccupancies(plan.id());
                    occupancies.put(plan.id(), occ);
                    Instant firstStart = firstOccupancy(occ).startUtc();
                    if (!firstStart.isAfter(now)) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", "PLAN_ALREADY_STARTED");
                        detail.put("scheduleKey", plan.scheduleKey());
                        detail.put("firstStartUtc", firstStart.toString());
                        detail.put("nowUtc", now.toString());
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                                "PLAN_ALREADY_STARTED",
                                "已开始计划不可作为候选改签: " + plan.scheduleKey(), List.of(detail));
                    }
                }
                // 按当前始发时刻升序计算后移量，前车先定位，后车约束基于已定位的前车
                Set<Long> batchIds = new HashSet<>();
                plans.forEach(p -> batchIds.add(p.id()));
                List<DayPlan> ordered = plans.stream()
                        .sorted(Comparator.comparing(p -> firstOccupancy(occupancies.get(p.id())).startUtc()))
                        .toList();
                Map<Long, List<Occupancy>> shifted = new HashMap<>();
                Map<Long, ShiftResult> shifts = new HashMap<>();
                for (DayPlan plan : ordered) {
                    ShiftResult result = computeShift(plan, occupancies, shifted, batchIds);
                    shifted.put(plan.id(), result.occupancies());
                    shifts.put(plan.id(), result);
                }
                validateFinalState(plans, occupancies, shifted, batchIds);
                long nowMillis = System.currentTimeMillis();
                Map<Long, DayPlan> byId = new HashMap<>();
                plans.forEach(p -> byId.put(p.id(), p));
                List<GuaranteeDecisionView> decisions = new ArrayList<>();
                for (DayPlan plan : plans) {
                    ShiftResult result = shifts.get(plan.id());
                    int newVersion = plan.version() + 1;
                    planRepo.replaceOccupancies(plan.id(), result.occupancies());
                    planRepo.updateVersionAndStatus(plan.id(), newVersion, PlanStatus.PUBLISHED, nowMillis);
                    planRepo.updateConnectionRisk(plan.id(), null, nowMillis);
                    GuaranteeDecision decision = new GuaranteeDecision(0L, req.requestKey(), plan.id(),
                            newVersion, result.predecessorVersion(),
                            firstOccupancy(occupancies.get(plan.id())).startUtc(),
                            firstOccupancy(result.occupancies()).startUtc(),
                            result.shiftMinutes(), result.deficitMinutes(), nowMillis);
                    connRepo.insertDecision(decision);
                    decisions.add(new GuaranteeDecisionView(decision.batchKey(), plan.scheduleKey(),
                            newVersion, decision.predecessorVersion(), decision.oldFirstStartUtc(),
                            decision.newFirstStartUtc(), decision.shiftMinutes(),
                            decision.deficitMinutes()));
                }
                // 后车整体后移等价于前车延后：同事务内重算其全部直接接续
                for (DayPlan plan : plans) {
                    Instant oldArrival = lastOccupancy(occupancies.get(plan.id())).endUtc();
                    Instant newArrival = lastOccupancy(shifted.get(plan.id())).endUtc();
                    if (!newArrival.isAfter(oldArrival)) {
                        continue;
                    }
                    for (Connection conn : connRepo.findActiveByPredecessor(plan.id())) {
                        if (batchIds.contains(conn.successorPlanId())) {
                            continue;
                        }
                        evaluateConnection(conn, plan.id(), byId.get(plan.id()).version() + 1,
                                oldArrival, newArrival);
                    }
                }
                BatchGuaranteeResponse response = new BatchGuaranteeResponse(decisions);
                idemRepo.insert(OP_BATCH_GUARANTEE, req.requestKey(), hash, toJson(response), nowMillis);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_BATCH_GUARANTEE, req.requestKey(), hash,
                    BatchGuaranteeResponse.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED", "requestKey 冲突"));
        }
    }

    // ---------- 改签/取消联动（须在调用方事务内） ----------

    /**
     * 前车改签生效后，在同一事务内重算其全部直接接续：接续随链指向新计划，
     * 保障不足的后车生成不可改写的风险记录并标记 CONNECTION_AT_RISK（已开始计划除外），
     * 不自动修改后车时刻。
     */
    public void onPredecessorRescheduled(DayPlan oldPlan, DayPlan newPlan) {
        List<Connection> connections = connRepo.findActiveByPredecessor(oldPlan.id());
        if (connections.isEmpty()) {
            return;
        }
        Instant oldArrival = lastOccupancy(planRepo.findOccupancies(oldPlan.id())).endUtc();
        Instant newArrival = lastOccupancy(planRepo.findOccupancies(newPlan.id())).endUtc();
        for (Connection conn : connections) {
            connRepo.updatePredecessor(conn.id(), newPlan.id());
            evaluateConnection(conn, newPlan.id(), newPlan.version(), oldArrival, newArrival);
        }
    }

    /**
     * 计划取消后，在同一事务内取消其全部生效接续（历史保留），并重估相关后车风险标记。
     */
    public void onPlanCancelled(DayPlan plan) {
        List<Connection> connections = connRepo.findByPlan(plan.id()).stream()
                .filter(c -> c.status() == ConnectionStatus.ACTIVE)
                .toList();
        if (connections.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Connection conn : connections) {
            connRepo.cancel(conn.id(), now);
            if (conn.successorPlanId() != plan.id()) {
                refreshSuccessorRiskFlag(conn.successorPlanId());
            }
        }
    }

    // ---------- 内部实现 ----------

    /**
     * 评估单条接续：保障不足时追加风险记录（固化前车版本、原新终到时刻与保障差额），
     * 随后按后车全部生效接续重估其风险标记。
     */
    private void evaluateConnection(Connection conn, long predecessorPlanId, int predecessorVersion,
                                    Instant oldArrival, Instant newArrival) {
        Optional<DayPlan> successorOpt = planRepo.findById(conn.successorPlanId());
        if (successorOpt.isEmpty() || successorOpt.get().status() != PlanStatus.PUBLISHED) {
            return;
        }
        DayPlan successor = successorOpt.get();
        List<Occupancy> succOcc = planRepo.findOccupancies(successor.id());
        if (succOcc.isEmpty()) {
            return;
        }
        Instant firstStart = firstOccupancy(succOcc).startUtc();
        long actualMinutes = Duration.between(newArrival, firstStart).toMinutes();
        if (actualMinutes < conn.guaranteeMinutes()) {
            long deficit = conn.guaranteeMinutes() - actualMinutes;
            Instant windowStart = newArrival.plus(Duration.ofMinutes(conn.guaranteeMinutes()));
            Instant windowEnd = operationDayEnd(successor);
            connRepo.insertRisk(new ConnectionRisk(0L, conn.id(), predecessorPlanId,
                    predecessorVersion, successor.id(), oldArrival, newArrival,
                    conn.guaranteeMinutes(), actualMinutes, deficit, windowStart, windowEnd,
                    System.currentTimeMillis()));
        }
        refreshSuccessorRiskFlag(successor.id());
    }

    /**
     * 按后车全部生效接续重估风险标记：存在保障不足且后车未开始时标记
     * CONNECTION_AT_RISK，全部满足时清除标记；历史风险记录不受影响。
     */
    private void refreshSuccessorRiskFlag(long successorPlanId) {
        Optional<DayPlan> successorOpt = planRepo.findById(successorPlanId);
        if (successorOpt.isEmpty() || successorOpt.get().status() != PlanStatus.PUBLISHED) {
            return;
        }
        DayPlan successor = successorOpt.get();
        List<Occupancy> succOcc = planRepo.findOccupancies(successor.id());
        if (succOcc.isEmpty()) {
            return;
        }
        Instant firstStart = firstOccupancy(succOcc).startUtc();
        boolean violated = false;
        for (Connection conn : connRepo.findActiveBySuccessor(successor.id())) {
            Optional<DayPlan> predOpt = planRepo.findById(conn.predecessorPlanId());
            if (predOpt.isEmpty() || predOpt.get().status() != PlanStatus.PUBLISHED) {
                continue;
            }
            Instant predEnd = lastOccupancy(planRepo.findOccupancies(predOpt.get().id())).endUtc();
            if (Duration.between(predEnd, firstStart).toMinutes() < conn.guaranteeMinutes()) {
                violated = true;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (violated && firstStart.isAfter(Instant.now(clock))) {
            if (!DayPlan.RISK_CONNECTION_AT_RISK.equals(successor.connectionRisk())) {
                planRepo.updateConnectionRisk(successor.id(), DayPlan.RISK_CONNECTION_AT_RISK, now);
            }
        } else if (!violated && successor.connectionRisk() != null) {
            planRepo.updateConnectionRisk(successor.id(), null, now);
        }
    }

    /**
     * 计算单趟后车的整体后移结果：先取全部接续分钟数约束的最大者，
     * 再在不与线路已发布时隙及本批次已定位计划冲突的前提下寻找最早可行始发。
     */
    private ShiftResult computeShift(DayPlan plan, Map<Long, List<Occupancy>> occupancies,
                                     Map<Long, List<Occupancy>> shifted, Set<Long> batchIds) {
        List<Occupancy> base = occupancies.get(plan.id());
        Instant firstStart = firstOccupancy(base).startUtc();
        Instant required = null;
        int predecessorVersion = 0;
        long deficit = 0L;
        for (Connection conn : connRepo.findActiveBySuccessor(plan.id())) {
            Instant predEnd = shifted.containsKey(conn.predecessorPlanId())
                    ? lastOccupancy(shifted.get(conn.predecessorPlanId())).endUtc()
                    : lastOccupancy(planRepo.findOccupancies(conn.predecessorPlanId())).endUtc();
            Instant connRequired = predEnd.plus(Duration.ofMinutes(conn.guaranteeMinutes()));
            if (required == null || connRequired.isAfter(required)) {
                required = connRequired;
                predecessorVersion = shifted.containsKey(conn.predecessorPlanId())
                        ? predecessorVersionOf(conn.predecessorPlanId())
                        : planRepo.findById(conn.predecessorPlanId()).map(DayPlan::version).orElse(0);
            }
            deficit = Math.max(deficit,
                    conn.guaranteeMinutes() - Duration.between(predEnd, firstStart).toMinutes());
        }
        long shiftMillis = required == null ? 0L
                : Math.max(0L, required.toEpochMilli() - firstStart.toEpochMilli());
        Instant dayEnd = operationDayEnd(plan);
        while (true) {
            List<Occupancy> candidate = shiftOccupancies(base, shiftMillis);
            if (lastOccupancy(candidate).endUtc().isAfter(dayEnd)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "BATCH_NO_FEASIBLE_WINDOW");
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("requiredFirstStartUtc",
                        (required == null ? firstStart : required).toString());
                detail.put("windowEndUtc", dayEnd.toString());
                detail.put("deficitMinutes", deficit);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_NO_FEASIBLE_WINDOW",
                        "计划 " + plan.scheduleKey() + " 在运营日内找不到不冲突时间窗", List.of(detail));
            }
            long increment = conflictShiftIncrement(plan, candidate, shifted, batchIds);
            if (increment <= 0L) {
                long shiftMinutes = Duration.ofMillis(shiftMillis).toMinutes();
                return new ShiftResult(candidate, shiftMinutes, deficit, predecessorVersion);
            }
            shiftMillis += increment;
        }
    }

    /**
     * 候选占用与线路已发布时隙（排除本批次）及本批次已定位计划的冲突检测；
     * 返回消除冲突所需的最小附加后移毫秒数，无冲突返回 0。
     */
    private long conflictShiftIncrement(DayPlan plan, List<Occupancy> candidate,
                                        Map<Long, List<Occupancy>> shifted, Set<Long> batchIds) {
        List<String> sectionIds = candidate.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> slots = new ArrayList<>(
                planRepo.findPublishedSlots(plan.opDate(), sectionIds, batchIds));
        shifted.forEach((planId, occs) -> {
            if (planId != plan.id()) {
                occs.forEach(o -> slots.add(new PublishedSlot("BATCH-" + planId, o.trainNo(),
                        o.sectionId(), o.startUtc(), o.endUtc())));
            }
        });
        long increment = 0L;
        for (Occupancy occ : candidate) {
            for (PublishedSlot slot : slots) {
                if (!occ.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                boolean overlap = occ.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(occ.endUtc());
                if (overlap) {
                    increment = Math.max(increment,
                            slot.endUtc().toEpochMilli() - occ.startUtc().toEpochMilli());
                }
            }
        }
        return increment;
    }

    /**
     * 提交前校验完整最终状态：全部接续分钟数与线路时隙（含批次内部）均满足，
     * 任一不满足整次 422，不产生任何写入。
     */
    private void validateFinalState(List<DayPlan> plans, Map<Long, List<Occupancy>> occupancies,
                                    Map<Long, List<Occupancy>> shifted, Set<Long> batchIds) {
        for (DayPlan plan : plans) {
            Instant firstStart = firstOccupancy(shifted.get(plan.id())).startUtc();
            for (Connection conn : connRepo.findActiveBySuccessor(plan.id())) {
                Instant predEnd = shifted.containsKey(conn.predecessorPlanId())
                        ? lastOccupancy(shifted.get(conn.predecessorPlanId())).endUtc()
                        : lastOccupancy(planRepo.findOccupancies(conn.predecessorPlanId())).endUtc();
                long actual = Duration.between(predEnd, firstStart).toMinutes();
                if (actual < conn.guaranteeMinutes()) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "BATCH_NO_FEASIBLE_WINDOW");
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("requiredMinutes", (long) conn.guaranteeMinutes());
                    detail.put("actualMinutes", actual);
                    detail.put("deficitMinutes", conn.guaranteeMinutes() - actual);
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "BATCH_NO_FEASIBLE_WINDOW",
                            "计划 " + plan.scheduleKey() + " 最终状态不满足全部接续分钟数",
                            List.of(detail));
                }
            }
            long increment = conflictShiftIncrement(plan, shifted.get(plan.id()), shifted, batchIds);
            if (increment > 0L) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "BATCH_NO_FEASIBLE_WINDOW");
                detail.put("scheduleKey", plan.scheduleKey());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_NO_FEASIBLE_WINDOW",
                        "计划 " + plan.scheduleKey() + " 最终状态存在时隙冲突", List.of(detail));
            }
        }
    }

    private void validateOperationDates(DayPlan pred, DayPlan succ, boolean crossMidnight) {
        if (pred.opDate().equals(succ.opDate())) {
            return;
        }
        if (succ.opDate().equals(pred.opDate().plusDays(1)) && crossMidnight) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "CONNECTION_OP_DATE_INVALID");
        detail.put("predecessorOpDate", pred.opDate().toString());
        detail.put("successorOpDate", succ.opDate().toString());
        detail.put("crossMidnight", crossMidnight);
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_OP_DATE_INVALID",
                "接续计划必须同运营日或明确跨零点: 前车=" + pred.opDate() + " 后车=" + succ.opDate(),
                List.of(detail));
    }

    private void validateStationContinuity(String transferStation, Occupancy predLast,
                                           Occupancy succFirst) {
        if (!transferStation.equals(predLast.toStation())
                || !transferStation.equals(succFirst.fromStation())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "CONNECTION_STATION_GAP");
            detail.put("transferStation", transferStation);
            detail.put("predecessorArrivalStation", predLast.toStation());
            detail.put("successorDepartureStation", succFirst.fromStation());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_STATION_GAP",
                    "站点不连续：换乘站 " + transferStation + " 与前车终到站/后车始发站不一致",
                    List.of(detail));
        }
    }

    private void validateGap(Instant predecessorArrival, Instant successorStart, int guarantee) {
        long actualMinutes = Duration.between(predecessorArrival, successorStart).toMinutes();
        if (actualMinutes < guarantee) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "CONNECTION_GAP_INSUFFICIENT");
            detail.put("requiredMinutes", (long) guarantee);
            detail.put("actualMinutes", actualMinutes);
            detail.put("deficitMinutes", guarantee - actualMinutes);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CONNECTION_GAP_INSUFFICIENT",
                    "接续时间不足：要求 " + guarantee + " 分钟，实际 " + actualMinutes + " 分钟",
                    List.of(detail));
        }
    }

    private List<Occupancy> shiftOccupancies(List<Occupancy> occupancies, long shiftMillis) {
        List<Occupancy> result = new ArrayList<>(occupancies.size());
        for (Occupancy o : occupancies) {
            result.add(new Occupancy(0L, o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                    o.fromStation(), o.toStation(),
                    o.startUtc().plusMillis(shiftMillis), o.endUtc().plusMillis(shiftMillis)));
        }
        return result;
    }

    private int predecessorVersionOf(long planId) {
        // 批次内前车版本取其决策后版本（当前版本 + 1）
        return planRepo.findById(planId).map(p -> p.version() + 1).orElse(0);
    }

    private Occupancy firstOccupancy(List<Occupancy> occupancies) {
        return occupancies.stream().min(Comparator.comparing(Occupancy::startUtc)).orElseThrow();
    }

    private Occupancy lastOccupancy(List<Occupancy> occupancies) {
        return occupancies.stream().max(Comparator.comparing(Occupancy::endUtc)).orElseThrow();
    }

    private Instant operationDayEnd(DayPlan plan) {
        return plan.opDate().plusDays(1).atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
    }

    private String scheduleKeyOf(long planId) {
        return planRepo.findById(planId).map(DayPlan::scheduleKey).orElse("UNKNOWN-" + planId);
    }

    private ConnectionView toView(Connection conn) {
        return new ConnectionView(conn.id(), scheduleKeyOf(conn.predecessorPlanId()),
                scheduleKeyOf(conn.successorPlanId()), conn.transferStation(),
                conn.guaranteeMinutes(), conn.crossMidnight(), conn.predecessorVersion(),
                conn.status().name());
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

    private String hashRegister(ConnectionRegisterRequest req, boolean crossMidnight) {
        return sha256(OP_CONN_REGISTER + '\n' + req.predecessorKey() + '\n' + req.successorKey()
                + '\n' + req.transferStation() + '\n' + req.guaranteeMinutes() + '\n' + crossMidnight);
    }

    private String hashBatch(BatchGuaranteeRequest req) {
        return sha256(OP_BATCH_GUARANTEE + '\n' + String.join("\n", req.scheduleKeys()));
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

    private ApiException connectionNotFound(long connectionId) {
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
     * 单趟后车的后移计算结果。
     *
     * @param occupancies       后移后的占用清单
     * @param shiftMinutes      整体后移分钟数
     * @param deficitMinutes    决策时修复的保障差额（各接续中最大缺口）
     * @param predecessorVersion 约束最紧接续的前车版本
     */
    private record ShiftResult(List<Occupancy> occupancies, long shiftMinutes,
                               long deficitMinutes, int predecessorVersion) {
    }
}
