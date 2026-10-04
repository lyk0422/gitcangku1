package com.example.starter.plan.service;

import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.GuaranteeDecision;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanConnectionState;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.RailConnection;
import com.example.starter.plan.model.RiskStatus;
import com.example.starter.plan.repo.ConnectionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConnectionDiagnosisView;
import com.example.starter.plan.web.dto.ConnectionEvaluationView;
import com.example.starter.plan.web.dto.ConnectionListItem;
import com.example.starter.plan.web.dto.ConnectionRiskView;
import com.example.starter.plan.web.dto.ConnectionView;
import com.example.starter.plan.web.dto.GuaranteeDecisionView;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleResponse;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * 铁路接续保障：接续登记/取消、改签后同事务重算与风险标记、批量保障改签与诊断查询。
 *
 * <p>并发与幂等约定与 PlanService 一致：写操作按 (操作类型, requestKey) 幂等，
 * 同键同参重放首次成功结果、异参 409、失败不占键；登记/取消/批量保障均先取全局发布锁，
 * 与发布、原子改签按事务提交顺序裁决。风险判定与改签决策记录只追加不改写。
 */
@Service
public class ConnectionService {

    /** 保障分钟数上限（含），即 24 小时。 */
    public static final int MAX_GUARANTEE_MINUTES = 1440;

    /** 批量保障改签单次延后分钟数上限（含）。 */
    public static final int MAX_SHIFT_MINUTES = 1440;

    private static final String OP_CONN_REGISTER = "CONNECTION_REGISTER";
    private static final String OP_CONN_CANCEL = "CONNECTION_CANCEL";
    private static final String OP_GUARANTEE_RESCHEDULE = "GUARANTEE_RESCHEDULE";

    private static final long MINUTE_MILLIS = 60_000L;

    private final PlanRepository planRepo;
    private final ConnectionRepository connRepo;
    private final IdempotencyRepository idemRepo;
    private final SlotChecker slotChecker;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ConnectionService(PlanRepository planRepo, ConnectionRepository connRepo,
                             IdempotencyRepository idemRepo, SlotChecker slotChecker,
                             ObjectMapper objectMapper, PlatformTransactionManager txManager,
                             Clock clock) {
        this.planRepo = planRepo;
        this.connRepo = connRepo;
        this.idemRepo = idemRepo;
        this.slotChecker = slotChecker;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 登记接续：两趟已发布计划，站点连续（前车终到站 = 后车始发站），
     * 同运营日或明确跨零点，且后车始发不早于前车终到加保障分钟数。
     * 任一校验失败返回 422 并携带实际值/要求值/差额，不产生任何写入。
     */
    public ConnectionView register(RegisterConnectionRequest req) {
        int guarantee = req.guaranteeMinutes();
        if (guarantee < 0 || guarantee > MAX_GUARANTEE_MINUTES) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("actualMinutes", guarantee);
            detail.put("minMinutes", 0);
            detail.put("maxMinutes", MAX_GUARANTEE_MINUTES);
            throw unprocessable("INVALID_GUARANTEE_MINUTES",
                    "保障分钟数须在 0~" + MAX_GUARANTEE_MINUTES + " 之间，实际: " + guarantee,
                    List.of(detail));
        }
        if (req.predecessorScheduleKey().equals(req.successorScheduleKey())) {
            throw badRequest("前后车计划必须不同: " + req.predecessorScheduleKey());
        }
        String hash = hashRegister(req);
        Optional<ConnectionView> replay =
                replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复查：并发下同键请求在锁外尚未见首次结果，进入事务后按重放裁决
                Optional<ConnectionView> replayed =
                        replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                DayPlan pred = planRepo.findByKeyForUpdate(req.predecessorScheduleKey())
                        .orElseThrow(() -> notFound(req.predecessorScheduleKey()));
                DayPlan succ = planRepo.findByKeyForUpdate(req.successorScheduleKey())
                        .orElseThrow(() -> notFound(req.successorScheduleKey()));
                requirePublished(pred);
                requirePublished(succ);
                List<Occupancy> predOcc = planRepo.findOccupancies(pred.id());
                List<Occupancy> succOcc = planRepo.findOccupancies(succ.id());
                Occupancy last = lastOccupancy(predOcc);
                Occupancy first = firstOccupancy(succOcc);
                String arrivalStation = destinationStation(last.sectionId());
                String departureStation = originStation(first.sectionId());
                if (!arrivalStation.equals(departureStation)) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("arrivalStation", arrivalStation);
                    detail.put("departureStation", departureStation);
                    throw unprocessable("STATION_NOT_CONTINUOUS",
                            "站点不连续：前车终到站 " + arrivalStation + " 与后车始发站 "
                                    + departureStation + " 不一致",
                            List.of(detail));
                }
                Instant arrival = last.endUtc();
                Instant departure = first.startUtc();
                LocalDate departureDay = departure.atZone(PlanService.OPERATION_ZONE).toLocalDate();
                boolean sameDay = departureDay.equals(pred.opDate());
                boolean nextDay = departureDay.equals(pred.opDate().plusDays(1));
                if (!sameDay && !(nextDay && req.crossMidnight())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("predecessorOpDate", pred.opDate().toString());
                    detail.put("departureDay", departureDay.toString());
                    detail.put("crossMidnight", req.crossMidnight());
                    throw unprocessable("OP_DATE_MISMATCH",
                            "接续须同运营日或明确跨零点：前车运营日 " + pred.opDate()
                                    + "，后车始发日 " + departureDay,
                            List.of(detail));
                }
                Instant required = arrival.plus(guarantee, ChronoUnit.MINUTES);
                if (departure.isBefore(required)) {
                    throw guaranteeNotMet(null, required, departure);
                }
                if (connRepo.findActivePair(pred.id(), succ.id()).isPresent()) {
                    throw conflict("CONNECTION_EXISTS",
                            "同一前后车组合已存在生效接续: " + pred.scheduleKey() + " -> "
                                    + succ.scheduleKey());
                }
                long now = clock.millis();
                long id = connRepo.insertConnection(req.connectionKey(), pred.id(), succ.id(),
                        guarantee, req.crossMidnight(), pred.version(),
                        arrival.toEpochMilli(), departure.toEpochMilli(), now);
                ConnectionView view = toView(connRepo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("接续写入后读取失败: " + id)));
                idemRepo.insert(OP_CONN_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_REGISTER, req.requestKey(), hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_KEY_EXISTS",
                            "connectionKey 已存在: " + req.connectionKey()));
        }
    }
    /**
     * 取消生效接续：关系保留为 CANCELLED 历史；若后车风险标记由该接续触发则清除。
     * 历史风险判定记录不改写。
     */
    public ConnectionView cancel(String connectionKey, String requestKey) {
        String hash = sha256(OP_CONN_CANCEL + '\n' + connectionKey);
        Optional<ConnectionView> replay =
                replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<ConnectionView> replayed =
                        replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionView.class);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                RailConnection conn = connRepo.findByKeyForUpdate(connectionKey)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "CONNECTION_NOT_FOUND", "接续不存在: " + connectionKey));
                if (conn.status() != ConnectionStatus.ACTIVE) {
                    throw conflict("CONNECTION_STATE_CONFLICT",
                            "仅生效接续可取消，当前状态: " + conn.status());
                }
                long now = clock.millis();
                connRepo.updateStatus(conn.id(), ConnectionStatus.CANCELLED, now);
                clearRiskIfTriggered(conn.successorPlanId(), conn.id(), now);
                ConnectionView view = toView(connRepo.findById(conn.id())
                        .orElseThrow(() -> new IllegalStateException("接续读取失败: " + conn.id())));
                idemRepo.insert(OP_CONN_CANCEL, requestKey, hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionView.class)
                    .orElseThrow(() -> conflict("CONNECTION_KEY_EXISTS",
                            "connectionKey 冲突: " + connectionKey));
        }
    }

    /**
     * 接续明细（含实时评估；已取消接续评估字段为 null）。不存在返回 404。
     */
    public ConnectionView getConnection(String connectionKey) {
        RailConnection conn = connRepo.findByKey(connectionKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CONNECTION_NOT_FOUND", "接续不存在: " + connectionKey));
        return toView(conn);
    }

    /**
     * 计划参与的全部接续（含已取消历史，任一端角色），按创建顺序。计划不存在返回 404。
     */
    public List<ConnectionListItem> listPlanConnections(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findAllByPlan(plan.id()).stream()
                .map(conn -> new ConnectionListItem(
                        conn.predecessorPlanId() == plan.id() ? "PREDECESSOR" : "SUCCESSOR",
                        toView(conn)))
                .toList();
    }

    /**
     * 计划作为后车的风险判定历史（只读，追加后不可变），按判定顺序。计划不存在返回 404。
     */
    public List<ConnectionRiskView> listRisks(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findRisksBySuccessor(plan.id()).stream()
                .map(this::toRiskView)
                .toList();
    }

    /**
     * 计划接续诊断：当前风险标记与全部生效接续的实时评估（只读，不改变状态）。
     */
    public ConnectionDiagnosisView diagnose(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        String riskStatus = connRepo.findState(plan.id())
                .map(s -> s.riskStatus().name())
                .orElse(RiskStatus.NONE.name());
        List<ConnectionEvaluationView> evaluations = new ArrayList<>();
        for (RailConnection conn : connRepo.findActiveByPredecessor(plan.id())) {
            evaluations.add(evaluate(conn, "PREDECESSOR"));
        }
        for (RailConnection conn : connRepo.findActiveBySuccessor(plan.id())) {
            evaluations.add(evaluate(conn, "SUCCESSOR"));
        }
        return new ConnectionDiagnosisView(scheduleKey, riskStatus, evaluations);
    }

    /**
     * 计划作为后车的保障改签决策历史（只读，追加后不可变），按决策顺序。计划不存在返回 404。
     */
    public List<GuaranteeDecisionView> listDecisions(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findDecisionsBySuccessor(plan.id()).stream()
                .map(this::toDecisionView)
                .toList();
    }
    /**
     * 批量保障改签：对选定的一组风险后车整体延后指定分钟数。
     * 全局发布锁内先校验完整最终状态（时隙、全部接续分钟数、运营日边界），
     * 任一计划找不到不冲突时间窗则整次 422，已发布时刻、风险标记与时隙占用均不变。
     */
    public GuaranteeRescheduleResponse batchGuaranteeReschedule(GuaranteeRescheduleRequest req) {
        Set<String> seenKeys = new HashSet<>();
        for (GuaranteeRescheduleRequest.Item item : req.items()) {
            int shift = item.shiftMinutes();
            if (shift < 1 || shift > MAX_SHIFT_MINUTES) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("scheduleKey", item.scheduleKey());
                detail.put("actualMinutes", shift);
                detail.put("minMinutes", 1);
                detail.put("maxMinutes", MAX_SHIFT_MINUTES);
                throw unprocessable("INVALID_SHIFT_MINUTES",
                        "延后分钟数须在 1~" + MAX_SHIFT_MINUTES + " 之间，计划 "
                                + item.scheduleKey() + " 实际: " + shift,
                        List.of(detail));
            }
            if (!seenKeys.add(item.scheduleKey())) {
                throw badRequest("批量项计划重复: " + item.scheduleKey());
            }
        }
        String hash = hashBatch(req);
        Optional<GuaranteeRescheduleResponse> replay = replayIfPresent(
                OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash, GuaranteeRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<GuaranteeRescheduleResponse> replayed = replayIfPresent(
                        OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash,
                        GuaranteeRescheduleResponse.class);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                long nowMillis = clock.millis();
                Instant now = clock.instant();
                List<PlanShift> shifts = new ArrayList<>();
                for (GuaranteeRescheduleRequest.Item item : req.items()) {
                    DayPlan plan = planRepo.findByKeyForUpdate(item.scheduleKey())
                            .orElseThrow(() -> notFound(item.scheduleKey()));
                    if (plan.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅已发布计划可保障改签，计划 " + item.scheduleKey()
                                        + " 当前状态: " + plan.status());
                    }
                    PlanConnectionState state = connRepo.findState(plan.id()).orElse(null);
                    if (state == null || state.riskStatus() != RiskStatus.CONNECTION_AT_RISK) {
                        throw conflict("CONNECTION_NOT_AT_RISK",
                                "计划未被标记为接续风险，不可保障改签: " + item.scheduleKey());
                    }
                    List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
                    Instant departure = firstOccupancy(occupancies).startUtc();
                    if (!departure.isAfter(now)) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("scheduleKey", item.scheduleKey());
                        detail.put("departureUtc", departure.toString());
                        detail.put("nowUtc", now.toString());
                        throw unprocessable("PLAN_ALREADY_STARTED",
                                "计划已开始，不可保障改签: " + item.scheduleKey(), List.of(detail));
                    }
                    long shiftMillis = item.shiftMinutes() * MINUTE_MILLIS;
                    List<Occupancy> shifted = occupancies.stream()
                            .map(o -> new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(),
                                    o.sectionId(), o.startUtc().plusMillis(shiftMillis),
                                    o.endUtc().plusMillis(shiftMillis)))
                            .toList();
                    Instant dayStart = plan.opDate()
                            .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
                    Instant dayEnd = plan.opDate().plusDays(1)
                            .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
                    for (Occupancy o : shifted) {
                        if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("scheduleKey", item.scheduleKey());
                            detail.put("shiftMinutes", item.shiftMinutes());
                            detail.put("opDate", plan.opDate().toString());
                            detail.put("dayEndUtc", dayEnd.toString());
                            detail.put("shiftedEndUtc", o.endUtc().toString());
                            throw unprocessable("SHIFT_OUT_OF_OPERATION_DAY",
                                    "延后后占用超出运营日 " + plan.opDate() + ": "
                                            + item.scheduleKey(),
                                    List.of(detail));
                        }
                    }
                    shifts.add(new PlanShift(plan, state.riskConnectionId(), occupancies,
                            shifted, item.shiftMinutes()));
                }
                // 时隙校验：排除整批计划后与其他已发布计划比对，再校验批内两两重叠
                List<Long> batchPlanIds = shifts.stream().map(s -> s.plan().id()).toList();
                List<Map<String, Object>> conflicts = new ArrayList<>();
                for (PlanShift s : shifts) {
                    conflicts.addAll(slotChecker.findSectionConflicts(s.plan(), s.shifted(),
                            batchPlanIds));
                }
                for (int i = 0; i < shifts.size(); i++) {
                    for (int j = i + 1; j < shifts.size(); j++) {
                        conflicts.addAll(findPairwiseConflicts(shifts.get(i), shifts.get(j)));
                    }
                }
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SLOT_CONFLICT",
                            "批量保障改签存在时隙冲突，整次未生效", conflicts);
                }
                // 接续分钟数校验：按整批改签后的最终状态裁决
                Map<Long, List<Occupancy>> finalOccupancies = new LinkedHashMap<>();
                for (PlanShift s : shifts) {
                    finalOccupancies.put(s.plan().id(), s.shifted());
                }
                for (PlanShift s : shifts) {
                    for (RailConnection conn : connRepo.findActiveBySuccessor(s.plan().id())) {
                        Instant predArrival = arrivalOf(conn.predecessorPlanId(), finalOccupancies);
                        Instant required = predArrival.plus(conn.guaranteeMinutes(),
                                ChronoUnit.MINUTES);
                        Instant actual = firstOccupancy(s.shifted()).startUtc();
                        if (actual.isBefore(required)) {
                            throw guaranteeNotMet(conn.connectionKey(), required, actual);
                        }
                    }
                    for (RailConnection conn : connRepo.findActiveByPredecessor(s.plan().id())) {
                        Instant newArrival = lastOccupancy(s.shifted()).endUtc();
                        Instant required = newArrival.plus(conn.guaranteeMinutes(),
                                ChronoUnit.MINUTES);
                        Instant succDeparture = departureOf(conn.successorPlanId(),
                                finalOccupancies);
                        if (succDeparture.isBefore(required)) {
                            throw guaranteeNotMet(conn.connectionKey(), required, succDeparture);
                        }
                    }
                }
                // 全部校验通过后统一落库：平移占用、版本加一、清除风险标记、追加决策历史
                List<GuaranteeRescheduleResponse.ItemResult> results = new ArrayList<>();
                for (PlanShift s : shifts) {
                    List<Occupancy> replacement = s.shifted().stream()
                            .map(o -> new Occupancy(0L, s.plan().id(), o.seq(), o.trainNo(),
                                    o.sectionId(), o.startUtc(), o.endUtc()))
                            .toList();
                    planRepo.replaceOccupancies(s.plan().id(), replacement);
                    planRepo.updateVersionAndStatus(s.plan().id(), s.plan().version() + 1,
                            PlanStatus.PUBLISHED, nowMillis);
                    connRepo.upsertState(s.plan().id(), RiskStatus.NONE, null, nowMillis);
                    recordDecision(req.requestKey(), s, finalOccupancies, nowMillis);
                    results.add(new GuaranteeRescheduleResponse.ItemResult(
                            s.plan().scheduleKey(),
                            firstOccupancy(s.current()).startUtc(),
                            firstOccupancy(s.shifted()).startUtc(),
                            s.shiftMinutes(), s.plan().version() + 1));
                }
                GuaranteeRescheduleResponse response = new GuaranteeRescheduleResponse(results);
                idemRepo.insert(OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash,
                        toJson(response), nowMillis);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_GUARANTEE_RESCHEDULE, req.requestKey(), hash,
                    GuaranteeRescheduleResponse.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestKey 冲突: " + req.requestKey()));
        }
    }

    /**
     * 原子改签事务内的接续重算钩子：接续指向新计划并重新判定保障分钟数。
     * 保障不足时追加风险判定历史；后车未开始且已发布才标记 CONNECTION_AT_RISK，
     * 不自动修改后车时刻。保障恢复满足时清除由该接续触发的风险标记。
     */
    public void recomputeAfterReschedule(DayPlan oldPlan, DayPlan newPlan) {
        long now = clock.millis();
        List<Occupancy> oldOccupancies = planRepo.findOccupancies(oldPlan.id());
        for (RailConnection conn : connRepo.findActiveByPredecessor(oldPlan.id())) {
            connRepo.updatePredecessor(conn.id(), newPlan.id(), now);
            evaluateAfterEndpointChange(conn.id(), lastOccupancy(oldOccupancies).endUtc(),
                    null, now);
        }
        for (RailConnection conn : connRepo.findActiveBySuccessor(oldPlan.id())) {
            connRepo.updateSuccessor(conn.id(), newPlan.id(), now);
            clearRiskIfTriggered(oldPlan.id(), conn.id(), now);
            evaluateAfterEndpointChange(conn.id(), null,
                    firstOccupancy(oldOccupancies).startUtc(), now);
        }
    }
    // ---------- 内部实现 ----------

    /** 批量项的改签上下文。 */
    private record PlanShift(DayPlan plan, Long riskConnectionId, List<Occupancy> current,
                             List<Occupancy> shifted, int shiftMinutes) {
    }

    /**
     * 接续任一端改签后的重新判定：保障不足追加风险历史并按需标记；满足时清除本接续触发的标记。
     *
     * @param oldArrivalOrNull   前车侧改签时的旧终到时刻（后车侧改签为 null，表示到达未变）
     * @param oldDepartureOrNull 后车侧改签时的旧始发时刻（前车侧改签为 null，表示始发未变）
     */
    private void evaluateAfterEndpointChange(long connectionId, Instant oldArrivalOrNull,
                                             Instant oldDepartureOrNull, long now) {
        RailConnection conn = connRepo.findById(connectionId)
                .orElseThrow(() -> new IllegalStateException("接续缺失: " + connectionId));
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
        DayPlan succ = planRepo.findById(conn.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续后车缺失: " + conn));
        Instant newArrival = lastOccupancy(planRepo.findOccupancies(pred.id())).endUtc();
        Instant newDeparture = firstOccupancy(planRepo.findOccupancies(succ.id())).startUtc();
        Instant oldArrival = oldArrivalOrNull != null ? oldArrivalOrNull : newArrival;
        Instant oldDeparture = oldDepartureOrNull != null ? oldDepartureOrNull : newDeparture;
        long actualMinutes = minutesBetween(newArrival, newDeparture);
        if (!newDeparture.isBefore(newArrival.plus(conn.guaranteeMinutes(), ChronoUnit.MINUTES))) {
            clearRiskIfTriggered(succ.id(), conn.id(), now);
            return;
        }
        long shortfall = conn.guaranteeMinutes() - actualMinutes;
        Instant windowStart = newArrival.plus(conn.guaranteeMinutes(), ChronoUnit.MINUTES);
        Instant windowEnd = succ.opDate().plusDays(1)
                .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
        boolean started = !newDeparture.isAfter(clock.instant());
        boolean marked = !started && succ.status() == PlanStatus.PUBLISHED;
        if (marked) {
            connRepo.upsertState(succ.id(), RiskStatus.CONNECTION_AT_RISK, conn.id(), now);
        }
        connRepo.insertRisk(new ConnectionRisk(0L, conn.id(), pred.id(), succ.id(), pred.version(),
                oldArrival, newArrival, oldDeparture, newDeparture, conn.guaranteeMinutes(),
                actualMinutes, shortfall, windowStart, windowEnd, marked, now));
    }

    /**
     * 追加保障改签决策历史（依据触发风险的接续与改签前保障差额）。
     */
    private void recordDecision(String batchKey, PlanShift shift,
                                Map<Long, List<Occupancy>> finalOccupancies, long now) {
        if (shift.riskConnectionId() == null) {
            return;
        }
        Optional<RailConnection> connOpt = connRepo.findById(shift.riskConnectionId());
        if (connOpt.isEmpty() || connOpt.get().status() != ConnectionStatus.ACTIVE) {
            return;
        }
        RailConnection conn = connOpt.get();
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
        Instant predArrival = arrivalOf(conn.predecessorPlanId(), finalOccupancies);
        Instant oldDeparture = firstOccupancy(shift.current()).startUtc();
        long shortfallBefore = conn.guaranteeMinutes()
                - minutesBetween(predArrival, oldDeparture);
        connRepo.insertDecision(new GuaranteeDecision(0L, batchKey, conn.id(),
                conn.predecessorPlanId(), shift.plan().id(), pred.version(),
                oldDeparture, firstOccupancy(shift.shifted()).startUtc(),
                shift.shiftMinutes(), shortfallBefore, now));
    }

    /**
     * 批内两个计划改签后占用的两两重叠检测（左闭右开，相邻合法）。
     */
    private List<Map<String, Object>> findPairwiseConflicts(PlanShift a, PlanShift b) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy oa : a.shifted()) {
            for (Occupancy ob : b.shifted()) {
                if (!oa.sectionId().equals(ob.sectionId())) {
                    continue;
                }
                boolean overlap = oa.startUtc().isBefore(ob.endUtc())
                        && ob.startUtc().isBefore(oa.endUtc());
                if (overlap) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", oa.sectionId());
                    detail.put("scheduleKey", a.plan().scheduleKey());
                    detail.put("conflictingScheduleKey", b.plan().scheduleKey());
                    detail.put("trainNo", oa.trainNo());
                    detail.put("startUtc", oa.startUtc().toString());
                    detail.put("endUtc", oa.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 计划终到时刻：批内计划取改签后占用，其余取当前占用。
     */
    private Instant arrivalOf(long planId, Map<Long, List<Occupancy>> finalOccupancies) {
        List<Occupancy> occupancies = finalOccupancies.get(planId);
        if (occupancies == null) {
            occupancies = planRepo.findOccupancies(planId);
        }
        return lastOccupancy(occupancies).endUtc();
    }

    /**
     * 计划始发时刻：批内计划取改签后占用，其余取当前占用。
     */
    private Instant departureOf(long planId, Map<Long, List<Occupancy>> finalOccupancies) {
        List<Occupancy> occupancies = finalOccupancies.get(planId);
        if (occupancies == null) {
            occupancies = planRepo.findOccupancies(planId);
        }
        return firstOccupancy(occupancies).startUtc();
    }

    /**
     * 若计划风险标记由指定接续触发则清除。
     */
    private void clearRiskIfTriggered(long planId, long connectionId, long now) {
        connRepo.findState(planId).ifPresent(state -> {
            if (state.riskStatus() == RiskStatus.CONNECTION_AT_RISK
                    && state.riskConnectionId() != null
                    && state.riskConnectionId() == connectionId) {
                connRepo.upsertState(planId, RiskStatus.NONE, null, now);
            }
        });
    }

    /**
     * 实时评估一条生效接续：要求始发 = 前车终到加保障分钟数；富余为负即保障不足。
     */
    private ConnectionEvaluationView evaluate(RailConnection conn, String role) {
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
        DayPlan succ = planRepo.findById(conn.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续后车缺失: " + conn));
        Instant arrival = lastOccupancy(planRepo.findOccupancies(pred.id())).endUtc();
        Instant departure = firstOccupancy(planRepo.findOccupancies(succ.id())).startUtc();
        Instant required = arrival.plus(conn.guaranteeMinutes(), ChronoUnit.MINUTES);
        long slack = minutesBetween(required, departure);
        String otherKey = "PREDECESSOR".equals(role) ? succ.scheduleKey() : pred.scheduleKey();
        return new ConnectionEvaluationView(conn.connectionKey(), role, otherKey,
                conn.guaranteeMinutes(), required, departure, slack, slack >= 0);
    }

    private ConnectionView toView(RailConnection conn) {
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
        DayPlan succ = planRepo.findById(conn.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续后车缺失: " + conn));
        Instant required = null;
        Instant actual = null;
        Long slack = null;
        if (conn.status() == ConnectionStatus.ACTIVE) {
            Instant arrival = lastOccupancy(planRepo.findOccupancies(pred.id())).endUtc();
            actual = firstOccupancy(planRepo.findOccupancies(succ.id())).startUtc();
            required = arrival.plus(conn.guaranteeMinutes(), ChronoUnit.MINUTES);
            slack = minutesBetween(required, actual);
        }
        return new ConnectionView(conn.connectionKey(), pred.scheduleKey(), succ.scheduleKey(),
                conn.guaranteeMinutes(), conn.crossMidnight(), conn.status().name(),
                conn.regPredecessorVersion(), required, actual, slack);
    }

    private ConnectionRiskView toRiskView(ConnectionRisk risk) {
        RailConnection conn = connRepo.findById(risk.connectionId())
                .orElseThrow(() -> new IllegalStateException("风险记录接续缺失: " + risk));
        DayPlan pred = planRepo.findById(risk.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("风险记录前车缺失: " + risk));
        DayPlan succ = planRepo.findById(risk.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("风险记录后车缺失: " + risk));
        return new ConnectionRiskView(risk.id(), conn.connectionKey(), pred.scheduleKey(),
                succ.scheduleKey(), risk.predecessorVersion(), risk.oldArrivalUtc(),
                risk.newArrivalUtc(), risk.oldDepartureUtc(), risk.newDepartureUtc(),
                risk.guaranteeMinutes(), risk.actualMinutes(), risk.shortfallMinutes(),
                risk.windowStartUtc(), risk.windowEndUtc(), risk.marked(),
                Instant.ofEpochMilli(risk.createdAtMillis()));
    }

    private GuaranteeDecisionView toDecisionView(GuaranteeDecision decision) {
        RailConnection conn = connRepo.findById(decision.connectionId())
                .orElseThrow(() -> new IllegalStateException("决策记录接续缺失: " + decision));
        DayPlan pred = planRepo.findById(decision.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("决策记录前车缺失: " + decision));
        DayPlan succ = planRepo.findById(decision.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("决策记录后车缺失: " + decision));
        return new GuaranteeDecisionView(decision.id(), decision.batchKey(), conn.connectionKey(),
                pred.scheduleKey(), succ.scheduleKey(), decision.predecessorVersion(),
                decision.oldDepartureUtc(), decision.newDepartureUtc(), decision.shiftMinutes(),
                decision.shortfallBeforeMinutes(),
                Instant.ofEpochMilli(decision.createdAtMillis()));
    }

    /** 终到占用：结束时刻最大（并列取序号大者）。 */
    private Occupancy lastOccupancy(List<Occupancy> occupancies) {
        return occupancies.stream()
                .max(Comparator.comparing(Occupancy::endUtc).thenComparing(Occupancy::seq))
                .orElseThrow(() -> new IllegalStateException("计划无占用"));
    }

    /** 始发占用：开始时刻最小（并列取序号小者）。 */
    private Occupancy firstOccupancy(List<Occupancy> occupancies) {
        return occupancies.stream()
                .min(Comparator.comparing(Occupancy::startUtc).thenComparing(Occupancy::seq))
                .orElseThrow(() -> new IllegalStateException("计划无占用"));
    }

    /** 区段 ID 的始发站（首个 '-' 之前），无法解析时按站点不连续拒绝。 */
    private String originStation(String sectionId) {
        int idx = sectionId.indexOf('-');
        if (idx <= 0 || idx == sectionId.length() - 1) {
            throw unprocessable("STATION_NOT_CONTINUOUS",
                    "区段 ID 无法解析站点: " + sectionId, List.of());
        }
        return sectionId.substring(0, idx);
    }

    /** 区段 ID 的终到站（最后一个 '-' 之后），无法解析时按站点不连续拒绝。 */
    private String destinationStation(String sectionId) {
        int idx = sectionId.lastIndexOf('-');
        if (idx <= 0 || idx == sectionId.length() - 1) {
            throw unprocessable("STATION_NOT_CONTINUOUS",
                    "区段 ID 无法解析站点: " + sectionId, List.of());
        }
        return sectionId.substring(idx + 1);
    }

    /** 两时刻之间的整分钟数（向下取整，可为负）。 */
    private long minutesBetween(Instant from, Instant to) {
        return Math.floorDiv(to.toEpochMilli() - from.toEpochMilli(), MINUTE_MILLIS);
    }

    private ApiException guaranteeNotMet(String connectionKey, Instant required, Instant actual) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (connectionKey != null) {
            detail.put("connectionKey", connectionKey);
        }
        detail.put("requiredDepartureUtc", required.toString());
        detail.put("actualDepartureUtc", actual.toString());
        detail.put("shortfallMinutes", minutesBetween(actual, required));
        return unprocessable("GUARANTEE_NOT_MET",
                "接续保障分钟数不足：要求始发不早于 " + required + "，实际 " + actual,
                List.of(detail));
    }

    private void requirePublished(DayPlan plan) {
        if (plan.status() == PlanStatus.CANCELLED) {
            throw unprocessable("PLAN_CANCELLED",
                    "计划已取消: " + plan.scheduleKey(), List.of());
        }
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw unprocessable("PLAN_NOT_PUBLISHED",
                    "计划未发布: " + plan.scheduleKey() + "，当前状态: " + plan.status(), List.of());
        }
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

    private String hashRegister(RegisterConnectionRequest req) {
        return sha256(OP_CONN_REGISTER + '\n' + req.connectionKey() + '\n'
                + req.predecessorScheduleKey() + '\n' + req.successorScheduleKey() + '\n'
                + req.guaranteeMinutes() + '\n' + req.crossMidnight());
    }

    private String hashBatch(GuaranteeRescheduleRequest req) {
        StringBuilder sb = new StringBuilder(OP_GUARANTEE_RESCHEDULE);
        for (GuaranteeRescheduleRequest.Item item : req.items()) {
            sb.append('\n').append(item.scheduleKey()).append('|').append(item.shiftMinutes());
        }
        return sha256(sb.toString());
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

    private ApiException unprocessable(String code, String message,
                                       List<Map<String, Object>> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}