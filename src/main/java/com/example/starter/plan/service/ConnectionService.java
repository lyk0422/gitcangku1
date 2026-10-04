package com.example.starter.plan.service;

import com.example.starter.plan.model.Connection;
import com.example.starter.plan.model.ConnectionEvent;
import com.example.starter.plan.model.ConnectionEventType;
import com.example.starter.plan.model.ConnectionRisk;
import com.example.starter.plan.model.ConnectionStatus;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.Section;
import com.example.starter.plan.repo.ConnectionRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SectionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConnectionEventView;
import com.example.starter.plan.web.dto.ConnectionHistoryResponse;
import com.example.starter.plan.web.dto.ConnectionResponse;
import com.example.starter.plan.web.dto.GuaranteeRescheduleItem;
import com.example.starter.plan.web.dto.GuaranteeRescheduleRequest;
import com.example.starter.plan.web.dto.GuaranteeRescheduleResponse;
import com.example.starter.plan.web.dto.RegisterConnectionRequest;
import com.example.starter.plan.web.dto.RiskConnectionView;
import com.example.starter.plan.web.dto.RiskPlanView;
import com.example.starter.plan.web.dto.SectionRequest;
import com.example.starter.plan.web.dto.SectionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * 铁路接续保障：区段目录、接续登记/取消、改签联动风险判定、批量保障改签与诊断查询。
 *
 * <p>并发与幂等约定与 PlanService 一致：写操作经全局发布锁按事务提交顺序裁决，
 * 按 (操作类型, requestKey) 幂等，同键同参重放首次结果，同键异参 409，失败不占键。
 * 历史事件只追加不改写；后续取消接续或再次改签不回填旧事件。
 */
@Service
public class ConnectionService {

    /** 保障分钟数合法下限（含）。 */
    public static final int MIN_GUARANTEE_MINUTES = 5;

    /** 保障分钟数合法上限（含）。 */
    public static final int MAX_GUARANTEE_MINUTES = 240;

    private static final String OP_CONNECT = "CONNECT";
    private static final String OP_CONN_CANCEL = "CONN_CANCEL";
    private static final String OP_GUARANTEE = "GUARANTEE";
    private static final int MAX_SHIFT_SEARCH_ITERATIONS = 500;
    private static final long MINUTE_MILLIS = 60_000L;

    private final PlanRepository planRepo;
    private final ConnectionRepository connRepo;
    private final SectionRepository sectionRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ConnectionService(PlanRepository planRepo, ConnectionRepository connRepo,
                             SectionRepository sectionRepo, IdempotencyRepository idemRepo,
                             ObjectMapper objectMapper, PlatformTransactionManager txManager,
                             Clock clock) {
        this.planRepo = planRepo;
        this.connRepo = connRepo;
        this.sectionRepo = sectionRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 登记区段起讫站。同 sectionId 重复登记相同起讫站返回原记录；不同起讫站返回 409。
     */
    public SectionResponse registerSection(SectionRequest req) {
        Optional<Section> existing = sectionRepo.find(req.sectionId());
        if (existing.isPresent()) {
            return toSectionResponse(checkSameSection(existing.get(), req));
        }
        long now = clock.millis();
        try {
            sectionRepo.insert(req.sectionId(), req.fromStation(), req.toStation(), now);
        } catch (DuplicateKeyException e) {
            // 并发登记同一区段：重查并校验起讫站一致
            Section s = sectionRepo.find(req.sectionId())
                    .orElseThrow(() -> new IllegalStateException("区段登记冲突后缺失: " + req.sectionId()));
            return toSectionResponse(checkSameSection(s, req));
        }
        return toSectionResponse(sectionRepo.find(req.sectionId())
                .orElseThrow(() -> new IllegalStateException("区段登记后缺失: " + req.sectionId())));
    }

    private Section checkSameSection(Section s, SectionRequest req) {
        if (!s.fromStation().equals(req.fromStation()) || !s.toStation().equals(req.toStation())) {
            throw conflict("SECTION_CONFLICT", "区段已登记且起讫站不同: " + req.sectionId());
        }
        return s;
    }

    private SectionResponse toSectionResponse(Section s) {
        return new SectionResponse(s.sectionId(), s.fromStation(), s.toStation(),
                Instant.ofEpochMilli(s.createdAt()));
    }
    /**
     * 登记接续关系：两趟已发布计划，后车始发不早于前车终到加保障分钟数。
     * 站点不连续、保障分钟数越界、计划未发布（含已取消）、运营日不匹配且未明确跨零点、
     * 或登记时保障不足均返回 422；同一前后车重复登记返回 409。
     */
    public ConnectionResponse register(RegisterConnectionRequest req) {
        boolean crossesMidnight = Boolean.TRUE.equals(req.crossesMidnight());
        int guarantee = req.guaranteeMinutes();
        if (guarantee < MIN_GUARANTEE_MINUTES || guarantee > MAX_GUARANTEE_MINUTES) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "GUARANTEE_RANGE_INVALID");
            detail.put("actualMinutes", guarantee);
            detail.put("requiredMinMinutes", MIN_GUARANTEE_MINUTES);
            detail.put("requiredMaxMinutes", MAX_GUARANTEE_MINUTES);
            throw unprocessable("GUARANTEE_RANGE_INVALID",
                    "保障分钟数须在 " + MIN_GUARANTEE_MINUTES + "~" + MAX_GUARANTEE_MINUTES
                            + " 之间，实际: " + guarantee, List.of(detail));
        }
        String hash = hashConnect(req, crossesMidnight);
        Optional<ConnectionResponse> replay =
                replayIfPresent(OP_CONNECT, req.requestKey(), hash, ConnectionResponse.class);
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
                if (pred.id() == succ.id()) {
                    throw unprocessable("CONNECTION_INVALID", "前后车必须为不同计划: " + pred.scheduleKey());
                }
                requirePublished(pred, "前车");
                requirePublished(succ, "后车");
                validateOperationDays(pred, succ, crossesMidnight);
                Occupancy predLast = lastOccupancy(pred.id());
                Occupancy succFirst = firstOccupancy(succ.id());
                validateStationContinuity(predLast, succFirst);
                if (connRepo.findActive(pred.id(), succ.id()).isPresent()) {
                    throw conflict("CONNECTION_EXISTS",
                            "前后车接续已存在: " + pred.scheduleKey() + " -> " + succ.scheduleKey());
                }
                long actual = Duration.between(predLast.endUtc(), succFirst.startUtc()).toMinutes();
                if (actual < guarantee) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "GUARANTEE_INSUFFICIENT");
                    detail.put("requiredMinutes", guarantee);
                    detail.put("actualMinutes", actual);
                    detail.put("shortfallMinutes", guarantee - actual);
                    throw unprocessable("GUARANTEE_INSUFFICIENT",
                            "登记时接续保障不足：要求 " + guarantee + " 分钟，实际 " + actual + " 分钟",
                            List.of(detail));
                }
                long now = clock.millis();
                long connId = connRepo.insert(pred.id(), succ.id(), guarantee, crossesMidnight, now);
                connRepo.insertEvent(new ConnectionEvent(0L, connId, ConnectionEventType.REGISTERED,
                        pred.id(), succ.id(), pred.version(),
                        predLast.endUtc(), succFirst.startUtc(),
                        guarantee, (int) actual, (int) (guarantee - actual),
                        null, null, now));
                ConnectionResponse response = toResponse(connRepo.findById(connId)
                        .orElseThrow(() -> new IllegalStateException("接续登记后缺失: " + connId)));
                idemRepo.insert(OP_CONNECT, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONNECT, req.requestKey(), hash, ConnectionResponse.class)
                    .orElseThrow(() -> conflict("CONNECTION_EXISTS", "前后车接续已存在"));
        }
    }

    private void requirePublished(DayPlan plan, String role) {
        if (plan.status() != PlanStatus.PUBLISHED) {
            throw unprocessable("PLAN_NOT_PUBLISHED",
                    role + "计划须为已发布状态，当前状态: " + plan.status()
                            + "，计划: " + plan.scheduleKey());
        }
    }

    /**
     * 运营日规则：同运营日，或明确跨零点时后车可在下一运营日。
     */
    private void validateOperationDays(DayPlan pred, DayPlan succ, boolean crossesMidnight) {
        if (succ.opDate().equals(pred.opDate())) {
            return;
        }
        if (crossesMidnight && succ.opDate().equals(pred.opDate().plusDays(1))) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "OP_DATE_MISMATCH");
        detail.put("predecessorOpDate", pred.opDate().toString());
        detail.put("successorOpDate", succ.opDate().toString());
        detail.put("crossesMidnight", crossesMidnight);
        throw unprocessable("OP_DATE_MISMATCH",
                "接续计划必须同运营日或明确跨零点：前车=" + pred.opDate() + " 后车=" + succ.opDate(),
                List.of(detail));
    }

    /**
     * 站点连续性：前车终到区段的终到站必须等于后车始发区段的始发站。
     */
    private void validateStationContinuity(Occupancy predLast, Occupancy succFirst) {
        Section predSection = sectionRepo.find(predLast.sectionId())
                .orElseThrow(() -> unprocessable("SECTION_UNKNOWN",
                        "前车终到区段未登记: " + predLast.sectionId()));
        Section succSection = sectionRepo.find(succFirst.sectionId())
                .orElseThrow(() -> unprocessable("SECTION_UNKNOWN",
                        "后车始发区段未登记: " + succFirst.sectionId()));
        if (!predSection.toStation().equals(succSection.fromStation())) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "STATION_NOT_CONTINUOUS");
            detail.put("arrivalStation", predSection.toStation());
            detail.put("departureStation", succSection.fromStation());
            throw unprocessable("STATION_NOT_CONTINUOUS",
                    "站点不连续：前车终到站 " + predSection.toStation()
                            + " 与后车始发站 " + succSection.fromStation() + " 不一致",
                    List.of(detail));
        }
    }

    /**
     * 取消接续：仅更新当前状态并追加 CANCELLED 事件，历史事件不改写；
     * 取消后若后车不再有任何保障不足的生效接续，则清除其风险标记。
     */
    public ConnectionResponse cancelConnection(long connectionId, String requestKey) {
        String hash = sha256(OP_CONN_CANCEL + '\n' + connectionId);
        Optional<ConnectionResponse> replay =
                replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionResponse.class);
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
                            "仅生效中的接续可取消，当前状态: " + conn.status());
                }
                long now = clock.millis();
                connRepo.cancel(connectionId, now);
                DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                        .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
                connRepo.insertEvent(new ConnectionEvent(0L, connectionId, ConnectionEventType.CANCELLED,
                        conn.predecessorPlanId(), conn.successorPlanId(), pred.version(),
                        null, null, null, null, null, null, null, now));
                clearRiskIfSatisfied(conn.successorPlanId(), now);
                ConnectionResponse response = toResponse(connRepo.findById(connectionId)
                        .orElseThrow(() -> new IllegalStateException("接续取消后缺失: " + connectionId)));
                idemRepo.insert(OP_CONN_CANCEL, requestKey, hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONN_CANCEL, requestKey, hash, ConnectionResponse.class)
                    .orElseThrow(() -> conflict("CONNECTION_STATE_CONFLICT", "接续取消冲突"));
        }
    }
    /**
     * 原子改签联动（由 PlanService 在同一事务内调用）：接续跟随到新计划并重算全部直接接续。
     * 保障不足且未开始的后车标记 CONNECTION_AT_RISK 并追加 RISK 事件（含可选改签时间窗），
     * 不自动修改后车时刻；已满足时尝试清除后车风险标记。
     */
    public void recomputeAfterReschedule(DayPlan oldPlan, DayPlan newPlan) {
        long now = clock.millis();
        for (Connection c : connRepo.findActiveByPredecessor(oldPlan.id())) {
            connRepo.updatePredecessor(c.id(), newPlan.id());
            Connection updated = connRepo.findById(c.id())
                    .orElseThrow(() -> new IllegalStateException("接续缺失: " + c.id()));
            evaluatePredecessorChange(updated, oldPlan, newPlan, now);
        }
        for (Connection c : connRepo.findActiveBySuccessor(oldPlan.id())) {
            connRepo.updateSuccessor(c.id(), newPlan.id());
            Connection updated = connRepo.findById(c.id())
                    .orElseThrow(() -> new IllegalStateException("接续缺失: " + c.id()));
            evaluateSuccessorChange(updated, oldPlan, newPlan, now);
        }
    }

    /**
     * 前车改签后的接续重算：old/new 时刻为前车终到的原值与新值。
     */
    private void evaluatePredecessorChange(Connection conn, DayPlan oldPred, DayPlan newPred, long now) {
        Instant oldEnd = lastOccupancy(oldPred.id()).endUtc();
        Instant newEnd = lastOccupancy(newPred.id()).endUtc();
        DayPlan succ = planRepo.findById(conn.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续后车缺失: " + conn));
        Instant succFirst = firstOccupancy(succ.id()).startUtc();
        long actual = Duration.between(newEnd, succFirst).toMinutes();
        if (actual < conn.guaranteeMinutes()) {
            // 已开始计划不可自动标记为候选改签：不标记、不生成风险事件
            if (succ.status() == PlanStatus.PUBLISHED && succFirst.toEpochMilli() > now) {
                planRepo.updateConnectionRisk(succ.id(), ConnectionRisk.CONNECTION_AT_RISK.name(), now);
                connRepo.insertEvent(riskEvent(conn, newPred, succ, oldEnd, newEnd, actual, now));
            }
        } else {
            clearRiskIfSatisfied(succ.id(), now);
        }
    }

    /**
     * 后车改签后的接续重算：old/new 时刻为后车始发的原值与新值。
     */
    private void evaluateSuccessorChange(Connection conn, DayPlan oldSucc, DayPlan newSucc, long now) {
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
        Instant predEnd = lastOccupancy(pred.id()).endUtc();
        Instant oldFirst = firstOccupancy(oldSucc.id()).startUtc();
        Instant newFirst = firstOccupancy(newSucc.id()).startUtc();
        long actual = Duration.between(predEnd, newFirst).toMinutes();
        if (actual < conn.guaranteeMinutes()) {
            if (newSucc.status() == PlanStatus.PUBLISHED && newFirst.toEpochMilli() > now) {
                planRepo.updateConnectionRisk(newSucc.id(), ConnectionRisk.CONNECTION_AT_RISK.name(), now);
                connRepo.insertEvent(riskEvent(conn, pred, newSucc, oldFirst, newFirst, actual, now));
            }
        } else {
            clearRiskIfSatisfied(newSucc.id(), now);
        }
    }

    /**
     * 生成 RISK 事件：固化前车版本、原新时刻、保障差额与可选改签时间窗。
     */
    private ConnectionEvent riskEvent(Connection conn, DayPlan pred, DayPlan succ,
                                      Instant oldTime, Instant newTime, long actual, long now) {
        Instant[] window = suggestWindow(conn, pred, succ);
        return new ConnectionEvent(0L, conn.id(), ConnectionEventType.RISK,
                pred.id(), succ.id(), pred.version(), oldTime, newTime,
                conn.guaranteeMinutes(), (int) actual, (int) (conn.guaranteeMinutes() - actual),
                window[0], window[1], now);
    }

    /**
     * 可选改签时间窗：[前车终到+保障分钟数, 后车保持当前跨度可放入运营日的最晚始发]。
     * 跨零点接续的运营日边界顺延一天；窗口仅作建议，不自动修改后车。
     */
    private Instant[] suggestWindow(Connection conn, DayPlan pred, DayPlan succ) {
        Instant predEnd = lastOccupancy(pred.id()).endUtc();
        List<Occupancy> occs = planRepo.findOccupancies(succ.id());
        Instant first = occs.stream().map(Occupancy::startUtc).min(Comparator.naturalOrder()).orElseThrow();
        Instant last = occs.stream().map(Occupancy::endUtc).max(Comparator.naturalOrder()).orElseThrow();
        Instant windowStart = predEnd.plus(conn.guaranteeMinutes(), ChronoUnit.MINUTES);
        Instant dayEnd = operationDayEnd(succ, conn.crossesMidnight());
        Instant windowEnd = dayEnd.minusMillis(Duration.between(first, last).toMillis());
        return new Instant[] {windowStart, windowEnd};
    }

    /**
     * 后车风险标记清除：仅当后车当前被标记且全部生效接续保障均满足时清除。
     */
    private void clearRiskIfSatisfied(long successorPlanId, long now) {
        if (!ConnectionRisk.CONNECTION_AT_RISK.name().equals(planRepo.findConnectionRisk(successorPlanId))) {
            return;
        }
        DayPlan succ = planRepo.findById(successorPlanId)
                .orElseThrow(() -> new IllegalStateException("计划缺失: " + successorPlanId));
        if (succ.status() != PlanStatus.PUBLISHED) {
            return;
        }
        Instant succFirst = firstOccupancy(successorPlanId).startUtc();
        for (Connection c : connRepo.findActiveBySuccessor(successorPlanId)) {
            Instant predEnd = lastOccupancy(c.predecessorPlanId()).endUtc();
            if (Duration.between(predEnd, succFirst).toMinutes() < c.guaranteeMinutes()) {
                return;
            }
        }
        planRepo.updateConnectionRisk(successorPlanId, ConnectionRisk.NONE.name(), now);
    }

    /**
     * 计划运营日结束边界（左闭右开的右端）；跨零点接续顺延一天。
     */
    private Instant operationDayEnd(DayPlan plan, boolean crossesMidnight) {
        Instant dayEnd = plan.opDate().plusDays(1)
                .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
        return crossesMidnight ? dayEnd.plus(1, ChronoUnit.DAYS) : dayEnd;
    }

    /**
     * 计划首条占用（开始时刻最早，并列取序号最小）。
     */
    private Occupancy firstOccupancy(long planId) {
        return planRepo.findOccupancies(planId).stream()
                .min(Comparator.comparing(Occupancy::startUtc).thenComparing(Occupancy::seq))
                .orElseThrow(() -> new IllegalStateException("计划无占用: " + planId));
    }

    /**
     * 计划末条占用（结束时刻最晚，并列取序号最大）。
     */
    private Occupancy lastOccupancy(long planId) {
        return planRepo.findOccupancies(planId).stream()
                .max(Comparator.comparing(Occupancy::endUtc).thenComparing(Occupancy::seq))
                .orElseThrow(() -> new IllegalStateException("计划无占用: " + planId));
    }
    /**
     * 批量保障改签：对选定的一组风险后车，在全局发布锁内先校验完整最终状态
     * （线路时隙与全部接续分钟数），再同事务提交平移。任一计划找不到不冲突时间窗
     * 或平移后仍存在保障不足时整次 422，已发布时刻、风险标记与时隙占用全部回滚。
     */
    public GuaranteeRescheduleResponse guaranteeReschedule(GuaranteeRescheduleRequest req) {
        List<String> keys = req.scheduleKeys();
        if (keys.stream().distinct().count() != keys.size()) {
            throw badRequest("scheduleKeys 存在重复计划");
        }
        String hash = hashGuarantee(req);
        Optional<GuaranteeRescheduleResponse> replay =
                replayIfPresent(OP_GUARANTEE, req.requestKey(), hash, GuaranteeRescheduleResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                long now = clock.millis();
                List<DayPlan> plans = new ArrayList<>();
                for (String key : keys) {
                    plans.add(planRepo.findByKeyForUpdate(key)
                            .orElseThrow(() -> notFound(key)));
                }
                for (DayPlan p : plans) {
                    if (p.status() != PlanStatus.PUBLISHED) {
                        throw conflict("PLAN_STATE_CONFLICT",
                                "仅已发布计划可批量保障改签，当前状态: " + p.status()
                                        + "，计划: " + p.scheduleKey());
                    }
                    if (!ConnectionRisk.CONNECTION_AT_RISK.name()
                            .equals(planRepo.findConnectionRisk(p.id()))) {
                        throw unprocessable("PLAN_NOT_AT_RISK",
                                "计划未处于接续风险状态: " + p.scheduleKey());
                    }
                }
                Set<Long> batchIds = new HashSet<>();
                plans.forEach(p -> batchIds.add(p.id()));
                // 按提交顺序依次为每个计划搜索可行平移量（考虑先处理计划的平移结果）
                Map<Long, Long> shifts = new LinkedHashMap<>();
                Map<Long, List<Occupancy>> shifted = new LinkedHashMap<>();
                Map<Long, Instant> oldFirstStarts = new LinkedHashMap<>();
                for (DayPlan p : plans) {
                    List<Occupancy> occs = planRepo.findOccupancies(p.id());
                    Instant firstStart = occs.stream().map(Occupancy::startUtc)
                            .min(Comparator.naturalOrder()).orElseThrow();
                    if (firstStart.toEpochMilli() <= now) {
                        throw unprocessable("PLAN_ALREADY_STARTED",
                                "计划已开始，不能作为候选改签: " + p.scheduleKey());
                    }
                    long shift = computeShift(p, occs, batchIds, shifted);
                    shifts.put(p.id(), shift);
                    oldFirstStarts.put(p.id(), firstStart);
                    shifted.put(p.id(), shiftOccupancies(occs, shift));
                }
                // 完整最终状态校验：平移后全部相关接续分钟数必须满足
                validateConnectionsAfterShift(plans, shifted);
                // 校验全部通过后统一提交修改
                List<GuaranteeRescheduleItem> items = new ArrayList<>();
                for (DayPlan p : plans) {
                    long shift = shifts.get(p.id());
                    planRepo.shiftOccupancies(p.id(), shift);
                    planRepo.updateVersionAndStatus(p.id(), p.version() + 1, PlanStatus.PUBLISHED, now);
                    planRepo.updateConnectionRisk(p.id(), ConnectionRisk.NONE.name(), now);
                    Instant oldFirst = oldFirstStarts.get(p.id());
                    items.add(new GuaranteeRescheduleItem(p.scheduleKey(), p.version() + 1,
                            oldFirst, oldFirst.plusMillis(shift), shift / MINUTE_MILLIS));
                }
                // 决策固化：每条生效接续记录 RESCHEDULED 事件（版本与时刻为决策后状态）
                for (DayPlan p : plans) {
                    Instant oldFirst = oldFirstStarts.get(p.id());
                    for (Connection c : connRepo.findActiveBySuccessor(p.id())) {
                        DayPlan pred = planRepo.findById(c.predecessorPlanId())
                                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + c));
                        Instant predEnd = lastOccupancy(pred.id()).endUtc();
                        Instant succFirst = firstOccupancy(p.id()).startUtc();
                        long actual = Duration.between(predEnd, succFirst).toMinutes();
                        connRepo.insertEvent(new ConnectionEvent(0L, c.id(),
                                ConnectionEventType.RESCHEDULED, pred.id(), p.id(), pred.version(),
                                oldFirst, succFirst, c.guaranteeMinutes(), (int) actual,
                                (int) (c.guaranteeMinutes() - actual), null, null, now));
                    }
                }
                GuaranteeRescheduleResponse response = new GuaranteeRescheduleResponse(items);
                idemRepo.insert(OP_GUARANTEE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_GUARANTEE, req.requestKey(), hash,
                    GuaranteeRescheduleResponse.class)
                    .orElseThrow(() -> conflict("GUARANTEE_CONFLICT", "批量保障改签冲突"));
        }
    }

    /**
     * 为单个风险后车搜索可行平移量：从接续要求的最小平移量起，逐次跳过冲突时隙，
     * 直到无冲突或超出运营日边界（找不到则抛 422 NO_FEASIBLE_WINDOW）。
     */
    private long computeShift(DayPlan plan, List<Occupancy> occs, Set<Long> batchIds,
                              Map<Long, List<Occupancy>> shifted) {
        Instant firstStart = occs.stream().map(Occupancy::startUtc)
                .min(Comparator.naturalOrder()).orElseThrow();
        long minShift = 0L;
        boolean crossesMidnight = false;
        for (Connection c : connRepo.findActiveBySuccessor(plan.id())) {
            Instant predEnd = effectiveLastEnd(c.predecessorPlanId(), shifted);
            long required = predEnd.toEpochMilli() + c.guaranteeMinutes() * MINUTE_MILLIS
                    - firstStart.toEpochMilli();
            minShift = Math.max(minShift, required);
            crossesMidnight |= c.crossesMidnight();
        }
        for (Connection c : connRepo.findActiveByPredecessor(plan.id())) {
            crossesMidnight |= c.crossesMidnight();
        }
        Instant dayEnd = operationDayEnd(plan, crossesMidnight);
        List<String> sectionIds = occs.stream().map(Occupancy::sectionId)
                .distinct().sorted().toList();
        List<PublishedSlot> slots = new ArrayList<>(
                planRepo.findPublishedSlots(plan.opDate(), sectionIds, batchIds));
        if (crossesMidnight) {
            slots.addAll(planRepo.findPublishedSlots(plan.opDate().plusDays(1), sectionIds, batchIds));
        }
        long shift = minShift;
        for (int i = 0; i < MAX_SHIFT_SEARCH_ITERATIONS; i++) {
            List<Occupancy> candidate = shiftOccupancies(occs, shift);
            Instant lastEnd = candidate.stream().map(Occupancy::endUtc)
                    .max(Comparator.naturalOrder()).orElseThrow();
            if (!lastEnd.isAfter(dayEnd)) {
                long next = nextShiftAfterConflicts(candidate, slots, shifted, shift);
                if (next < 0) {
                    return shift;
                }
                shift = next;
            } else {
                break;
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "NO_FEASIBLE_WINDOW");
        detail.put("scheduleKey", plan.scheduleKey());
        detail.put("requiredStartUtc", firstStart.plusMillis(minShift).toString());
        detail.put("dayEndUtc", dayEnd.toString());
        throw unprocessable("NO_FEASIBLE_WINDOW",
                "计划找不到不冲突时间窗: " + plan.scheduleKey(), List.of(detail));
    }

    /**
     * 检查候选占用与其他已发布时隙及本批次已确定平移的占用是否冲突；
     * 无冲突返回 -1，否则返回跳过最早冲突后的下一个候选平移量（严格大于当前值）。
     */
    private long nextShiftAfterConflicts(List<Occupancy> candidate, List<PublishedSlot> slots,
                                         Map<Long, List<Occupancy>> shifted, long currentShift) {
        long best = Long.MAX_VALUE;
        for (Occupancy o : candidate) {
            for (PublishedSlot s : slots) {
                if (o.sectionId().equals(s.sectionId())
                        && o.startUtc().isBefore(s.endUtc()) && s.startUtc().isBefore(o.endUtc())) {
                    long skip = s.endUtc().toEpochMilli() - o.startUtc().toEpochMilli();
                    if (skip > currentShift) {
                        best = Math.min(best, skip);
                    }
                }
            }
            for (List<Occupancy> other : shifted.values()) {
                for (Occupancy s : other) {
                    if (o.sectionId().equals(s.sectionId())
                            && o.startUtc().isBefore(s.endUtc()) && s.startUtc().isBefore(o.endUtc())) {
                        long skip = s.endUtc().toEpochMilli() - o.startUtc().toEpochMilli();
                        if (skip > currentShift) {
                            best = Math.min(best, skip);
                        }
                    }
                }
            }
        }
        if (best == Long.MAX_VALUE) {
            // 冲突只能由不大于当前平移量的跳过产生，理论上不发生；强制推进避免死循环
            return hasAnyConflict(candidate, slots, shifted) ? currentShift + 1 : -1;
        }
        return best;
    }

    private boolean hasAnyConflict(List<Occupancy> candidate, List<PublishedSlot> slots,
                                   Map<Long, List<Occupancy>> shifted) {
        for (Occupancy o : candidate) {
            for (PublishedSlot s : slots) {
                if (o.sectionId().equals(s.sectionId())
                        && o.startUtc().isBefore(s.endUtc()) && s.startUtc().isBefore(o.endUtc())) {
                    return true;
                }
            }
            for (List<Occupancy> other : shifted.values()) {
                for (Occupancy s : other) {
                    if (o.sectionId().equals(s.sectionId())
                            && o.startUtc().isBefore(s.endUtc()) && s.startUtc().isBefore(o.endUtc())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 平移后全部相关接续（本批次计划任一侧）分钟数最终校验，任一不足整次 422。
     */
    private void validateConnectionsAfterShift(List<DayPlan> plans,
                                               Map<Long, List<Occupancy>> shifted) {
        List<Map<String, Object>> violations = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (DayPlan p : plans) {
            List<Connection> related = new ArrayList<>(connRepo.findActiveBySuccessor(p.id()));
            related.addAll(connRepo.findActiveByPredecessor(p.id()));
            for (Connection c : related) {
                if (!seen.add(c.id())) {
                    continue;
                }
                Instant predEnd = effectiveLastEnd(c.predecessorPlanId(), shifted);
                Instant succFirst = effectiveFirstStart(c.successorPlanId(), shifted);
                long actual = Duration.between(predEnd, succFirst).toMinutes();
                if (actual < c.guaranteeMinutes()) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "GUARANTEE_INSUFFICIENT");
                    detail.put("connectionId", c.id());
                    detail.put("requiredMinutes", c.guaranteeMinutes());
                    detail.put("actualMinutes", actual);
                    detail.put("shortfallMinutes", c.guaranteeMinutes() - actual);
                    violations.add(detail);
                }
            }
        }
        if (!violations.isEmpty()) {
            throw unprocessable("GUARANTEE_INSUFFICIENT",
                    "批量改签后仍存在接续保障不足", violations);
        }
    }

    private Instant effectiveLastEnd(long planId, Map<Long, List<Occupancy>> shifted) {
        List<Occupancy> occs = shifted.get(planId);
        if (occs == null) {
            return lastOccupancy(planId).endUtc();
        }
        return occs.stream().map(Occupancy::endUtc).max(Comparator.naturalOrder()).orElseThrow();
    }

    private Instant effectiveFirstStart(long planId, Map<Long, List<Occupancy>> shifted) {
        List<Occupancy> occs = shifted.get(planId);
        if (occs == null) {
            return firstOccupancy(planId).startUtc();
        }
        return occs.stream().map(Occupancy::startUtc).min(Comparator.naturalOrder()).orElseThrow();
    }

    private List<Occupancy> shiftOccupancies(List<Occupancy> occs, long shiftMillis) {
        return occs.stream()
                .map(o -> new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                        o.startUtc().plusMillis(shiftMillis), o.endUtc().plusMillis(shiftMillis)))
                .toList();
    }
    /**
     * 接续明细查询（只读）。
     */
    public ConnectionResponse getConnection(long connectionId) {
        return toResponse(connRepo.findById(connectionId)
                .orElseThrow(() -> connectionNotFound(connectionId)));
    }

    /**
     * 接续历史查询：事件按 id 升序返回，读取不改变状态。
     */
    public ConnectionHistoryResponse getHistory(long connectionId) {
        connRepo.findById(connectionId).orElseThrow(() -> connectionNotFound(connectionId));
        List<ConnectionEventView> events = connRepo.findEvents(connectionId).stream()
                .map(this::toEventView)
                .toList();
        return new ConnectionHistoryResponse(connectionId, events);
    }

    /**
     * 查询与指定计划相关的全部接续（含已取消），按 id 升序。
     */
    public List<ConnectionResponse> getConnectionsForPlan(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound(scheduleKey));
        return connRepo.findByPlan(plan.id()).stream().map(this::toResponse).toList();
    }

    /**
     * 诊断查询：当前标记 CONNECTION_AT_RISK 的计划及其各生效接续的保障差额与建议时间窗。
     */
    public List<RiskPlanView> listRisks() {
        List<RiskPlanView> result = new ArrayList<>();
        for (DayPlan plan : planRepo.findAtRiskPlans()) {
            Instant firstStart = firstOccupancy(plan.id()).startUtc();
            List<RiskConnectionView> connections = new ArrayList<>();
            for (Connection c : connRepo.findActiveBySuccessor(plan.id())) {
                DayPlan pred = planRepo.findById(c.predecessorPlanId())
                        .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + c));
                Instant predEnd = lastOccupancy(pred.id()).endUtc();
                long actual = Duration.between(predEnd, firstStart).toMinutes();
                Optional<ConnectionEvent> latestRisk = connRepo.findLatestRiskEvent(c.id());
                connections.add(new RiskConnectionView(c.id(), pred.scheduleKey(),
                        c.guaranteeMinutes(), actual, c.guaranteeMinutes() - actual,
                        latestRisk.map(ConnectionEvent::windowStartUtc).orElse(null),
                        latestRisk.map(ConnectionEvent::windowEndUtc).orElse(null)));
            }
            result.add(new RiskPlanView(plan.scheduleKey(), firstStart, connections));
        }
        return result;
    }

    // ---------- 内部实现 ----------

    private ConnectionResponse toResponse(Connection conn) {
        DayPlan pred = planRepo.findById(conn.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续前车缺失: " + conn));
        DayPlan succ = planRepo.findById(conn.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("接续后车缺失: " + conn));
        long actual = Duration.between(lastOccupancy(pred.id()).endUtc(),
                firstOccupancy(succ.id()).startUtc()).toMinutes();
        return new ConnectionResponse(conn.id(), pred.scheduleKey(), succ.scheduleKey(),
                conn.guaranteeMinutes(), conn.crossesMidnight(), conn.status().name(),
                conn.guaranteeMinutes(), actual, actual - conn.guaranteeMinutes(),
                Instant.ofEpochMilli(conn.createdAt()),
                conn.cancelledAt() == null ? null : Instant.ofEpochMilli(conn.cancelledAt()));
    }

    private ConnectionEventView toEventView(ConnectionEvent e) {
        String predKey = planRepo.findById(e.predecessorPlanId())
                .orElseThrow(() -> new IllegalStateException("事件前车缺失: " + e)).scheduleKey();
        String succKey = planRepo.findById(e.successorPlanId())
                .orElseThrow(() -> new IllegalStateException("事件后车缺失: " + e)).scheduleKey();
        return new ConnectionEventView(e.id(), e.eventType().name(), e.connectionId(),
                predKey, succKey, e.predecessorVersion(), e.oldTimeUtc(), e.newTimeUtc(),
                e.requiredMinutes(), e.actualMinutes(), e.shortfallMinutes(),
                e.windowStartUtc(), e.windowEndUtc(), Instant.ofEpochMilli(e.createdAt()));
    }

    private String hashConnect(RegisterConnectionRequest req, boolean crossesMidnight) {
        return sha256(OP_CONNECT + '\n' + req.predecessorKey() + '\n' + req.successorKey()
                + '\n' + req.guaranteeMinutes() + '\n' + crossesMidnight);
    }

    private String hashGuarantee(GuaranteeRescheduleRequest req) {
        return sha256(OP_GUARANTEE + '\n' + String.join("\n", req.scheduleKeys()));
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

    private ApiException unprocessable(String code, String message,
                                       List<Map<String, Object>> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }
}