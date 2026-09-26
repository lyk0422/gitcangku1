package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.Rearrangement;
import com.example.starter.plan.model.RearrangementSegment;
import com.example.starter.plan.model.WeatherRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RearrangementRepository;
import com.example.starter.plan.repo.WeatherRestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.DiagnoseContributor;
import com.example.starter.plan.web.dto.DiagnoseResponse;
import com.example.starter.plan.web.dto.RearrangementSegmentView;
import com.example.starter.plan.web.dto.RearrangementView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.ReviseRestrictionRequest;
import com.example.starter.plan.web.dto.RevokeRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * 铁路气象限速核心业务：限速令登记/撤销/修订、明细/历史/诊断查询，
 * 以及发布/改签时的限速裁决与计划整体顺延重排。
 *
 * <p>裁决约定：限速令按区段与 UTC 左闭右开时段生效，同区段同时刻多条生效以最低速度为准；
 * 计划任一段与生效限速窗口相交即视为违反限速，须按线路既有最小间隔（计划内最短占用分钟数）
 * 的整数倍整体顺延，直至全部段避开限速窗口；已开始运行的计划不可重排。
 * 限速登记、撤销、修订与发布、改签共用全局发布锁，按事务提交顺序裁决。
 * 重排记录追加后不可变，撤销或修订限速令不改写已生成的重排记录。
 */
@Service
public class WeatherRestrictionService {

    /** 限速速度下限（km/h，含）。 */
    public static final int MIN_SPEED_KMH = 10;
    /** 限速速度上限（km/h，含）。 */
    public static final int MAX_SPEED_KMH = 300;

    private static final String OP_RESTRICT = "RESTRICT";
    private static final String OP_RESTRICT_REVOKE = "RESTRICT_REVOKE";
    private static final String OP_RESTRICT_REVISE = "RESTRICT_REVISE";

    private final WeatherRestrictionRepository restrictionRepo;
    private final RearrangementRepository rearrangementRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WeatherRestrictionService(WeatherRestrictionRepository restrictionRepo,
                                     RearrangementRepository rearrangementRepo,
                                     PlanRepository planRepo,
                                     IdempotencyRepository idemRepo,
                                     ObjectMapper objectMapper,
                                     Clock clock,
                                     PlatformTransactionManager txManager) {
        this.restrictionRepo = restrictionRepo;
        this.rearrangementRepo = rearrangementRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 单段受影响明细：原占用、与全部生效限速窗口的并集受影响分钟数、起决定作用（速度最低）的限速令。
     */
    public record SegmentImpact(Occupancy original, long affectedMinutes, WeatherRestriction binding) {
    }

    /**
     * 重排方案：整体顺延分钟数、顺延后占用清单与逐段受影响明细。
     */
    public record ShiftPlan(long shiftMinutes, List<Occupancy> shiftedOccupancies,
                            List<SegmentImpact> impacts) {
    }

    // ---------- 限速令登记 / 撤销 / 修订 ----------

    /**
     * 登记限速令（版本 1，状态 ACTIVE）。区段、时段或速度非法返回 422；
     * 业务键已存在（含已撤销历史）返回 409，应改用修订。
     */
    public RestrictionResponse register(RegisterRestrictionRequest req) {
        validateRestrictionParams(req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh());
        String hash = sha256(OP_RESTRICT + '\n' + req.restrictionKey() + '\n' + req.sectionId()
                + '\n' + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_RESTRICT, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                if (restrictionRepo.findMaxVersion(req.restrictionKey()).isPresent()) {
                    throw conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在，请使用修订: " + req.restrictionKey());
                }
                long now = System.currentTimeMillis();
                long id = restrictionRepo.insert(req.restrictionKey(), 1, req.sectionId(),
                        req.startUtc(), req.endUtc(), req.maxSpeedKmh(), req.operator(), now);
                RestrictionResponse response = toResponse(restrictionRepo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("限速令写入后读取失败: " + id)));
                idemRepo.insert(OP_RESTRICT, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESTRICT, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在: " + req.restrictionKey()));
        }
    }

    /**
     * 撤销当前生效版本：只翻转状态为 REVOKED，历史行不改写；撤销后不再影响后续发布或改签。
     * 业务键不存在返回 404；已撤销（无生效版本）返回 409。
     */
    public RestrictionResponse revoke(String restrictionKey, RevokeRestrictionRequest req) {
        String hash = sha256(OP_RESTRICT_REVOKE + '\n' + restrictionKey + '\n' + req.operator());
        Optional<RestrictionResponse> replay =
                replayIfPresent(OP_RESTRICT_REVOKE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                WeatherRestriction active = restrictionRepo.findActiveByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFoundOrStateConflict(restrictionKey));
                restrictionRepo.revoke(active.id());
                long now = System.currentTimeMillis();
                RestrictionResponse response = toResponse(restrictionRepo.findById(active.id())
                        .orElseThrow(() -> new IllegalStateException("限速令撤销后读取失败: " + active.id())));
                idemRepo.insert(OP_RESTRICT_REVOKE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESTRICT_REVOKE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令当前不可撤销: " + restrictionKey));
        }
    }

    /**
     * 修订限速令：同一事务内撤销当前生效版本并追加版本加一的新行；历史版本行不改写。
     * 业务键不存在返回 404；无生效版本（已撤销）返回 409。
     */
    public RestrictionResponse revise(String restrictionKey, ReviseRestrictionRequest req) {
        validateRestrictionParams(req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh());
        String hash = sha256(OP_RESTRICT_REVISE + '\n' + restrictionKey + '\n' + req.sectionId()
                + '\n' + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay =
                replayIfPresent(OP_RESTRICT_REVISE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                WeatherRestriction active = restrictionRepo.findActiveByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFoundOrStateConflict(restrictionKey));
                restrictionRepo.revoke(active.id());
                long now = System.currentTimeMillis();
                long id = restrictionRepo.insert(restrictionKey, active.version() + 1, req.sectionId(),
                        req.startUtc(), req.endUtc(), req.maxSpeedKmh(), req.operator(), now);
                RestrictionResponse response = toResponse(restrictionRepo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("限速令修订后读取失败: " + id)));
                idemRepo.insert(OP_RESTRICT_REVISE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RESTRICT_REVISE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令当前不可修订: " + restrictionKey));
        }
    }

    // ---------- 查询（只读，不改变状态） ----------

    /**
     * 限速令明细查询：按可选区段与是否仅生效过滤，按业务键、版本升序。
     */
    public List<RestrictionResponse> listRestrictions(String sectionId, boolean onlyActive) {
        return restrictionRepo.findAll(sectionId, onlyActive).stream()
                .map(this::toResponse).toList();
    }

    /**
     * 限速令历史查询：返回指定业务键的全部版本（含已撤销），按版本升序；不存在返回 404。
     */
    public List<RestrictionResponse> getRestrictionHistory(String restrictionKey) {
        List<WeatherRestriction> history = restrictionRepo.findHistory(restrictionKey);
        if (history.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESTRICTION_NOT_FOUND",
                    "限速令不存在: " + restrictionKey);
        }
        return history.stream().map(this::toResponse).toList();
    }

    /**
     * 限速诊断：指定区段与 UTC 左闭右开时段内，生效限速令的最低速度、
     * 各限速令相交分钟数。只读，不改变状态。
     */
    public DiagnoseResponse diagnose(String sectionId, Instant startUtc, Instant endUtc) {
        if (!endUtc.isAfter(startUtc)) {
            throw invalidWindow(startUtc, endUtc);
        }
        List<WeatherRestriction> active = restrictionRepo.findActiveOverlapping(
                List.of(sectionId), startUtc, endUtc);
        Integer effectiveSpeed = active.stream()
                .map(WeatherRestriction::maxSpeedKmh)
                .min(Comparator.naturalOrder())
                .orElse(null);
        List<DiagnoseContributor> contributors = active.stream()
                .map(r -> new DiagnoseContributor(r.restrictionKey(), r.version(), r.maxSpeedKmh(),
                        overlapMillis(startUtc, endUtc, r.startUtc(), r.endUtc()) / 60_000L))
                .toList();
        return new DiagnoseResponse(sectionId, startUtc, endUtc, effectiveSpeed, contributors);
    }

    /**
     * 计划重排历史查询：返回指定计划的全部重排记录（含逐段明细），按创建顺序升序；
     * 计划不存在返回 404。只读，不改变状态。
     */
    public List<RearrangementView> getRearrangements(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        return rearrangementRepo.findByPlanId(plan.id()).stream()
                .map(this::toView).toList();
    }

    /**
     * 查询计划最近一次重排记录视图；从未重排返回 null。供计划明细响应内嵌。
     */
    public RearrangementView latestRearrangement(long planId) {
        List<Rearrangement> headers = rearrangementRepo.findByPlanId(planId);
        if (headers.isEmpty()) {
            return null;
        }
        return toView(headers.get(headers.size() - 1));
    }

    // ---------- 发布/改签限速裁决（须在调用方事务与发布锁内使用） ----------

    /**
     * 发布/改签幂等指纹中的限速版本签名：计划涉及区段上全部生效限速令的
     * "业务键@版本" 有序拼接；无生效限速令时为空串。
     */
    public String restrictionSignature(Collection<String> sectionIds) {
        return restrictionRepo.findActiveBySections(sectionIds).stream()
                .map(r -> r.restrictionKey() + '@' + r.version())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /**
     * 限速裁决：计划任一段与生效限速窗口相交即违反限速，须整体顺延。
     * 无需顺延返回空；已开始运行的计划抛出 422（不可重排）；
     * 顺延至运营日结束仍无法避开限速抛出 422（无法满足）。
     *
     * <p>顺延量为线路既有最小间隔（计划内最短占用分钟数，不足 1 分钟按 1 分钟计）的最小整数倍，
     * 使顺延后全部段避开生效限速窗口且仍落在运营日内。
     */
    public Optional<ShiftPlan> planShift(DayPlan plan, List<Occupancy> occupancies) {
        if (occupancies.isEmpty()) {
            return Optional.empty();
        }
        Instant minStart = occupancies.stream().map(Occupancy::startUtc)
                .min(Comparator.naturalOrder()).orElseThrow();
        Instant maxEnd = occupancies.stream().map(Occupancy::endUtc)
                .max(Comparator.naturalOrder()).orElseThrow();
        Instant dayEnd = plan.opDate().plusDays(1)
                .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
        Collection<String> sections = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(Collectors.toCollection(TreeSet::new));
        // 顺延候选窗口不超过运营日结束，一次取全运营日剩余时段内的生效限速令
        List<WeatherRestriction> active = restrictionRepo.findActiveOverlapping(
                sections, minStart, dayEnd);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        List<SegmentImpact> impacts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            List<WeatherRestriction> hits = overlapping(active, o);
            if (!hits.isEmpty()) {
                impacts.add(new SegmentImpact(o, unionOverlapMinutes(o, hits), binding(hits)));
            }
        }
        if (impacts.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!minStart.isAfter(now)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("scheduleKey", plan.scheduleKey());
            detail.put("firstStartUtc", minStart.toString());
            detail.put("nowUtc", now.toString());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REARRANGE_NOT_ALLOWED",
                    "计划已开始运行，不可重排", List.of(detail));
        }
        long minIntervalMinutes = occupancies.stream()
                .mapToLong(o -> Math.max(1L, Duration.between(o.startUtc(), o.endUtc()).toMinutes()))
                .min().orElseThrow();
        for (long k = 1; ; k++) {
            long shiftMinutes = k * minIntervalMinutes;
            List<Occupancy> shifted = occupancies.stream()
                    .map(o -> shift(o, shiftMinutes)).toList();
            if (shifted.stream().anyMatch(o -> o.endUtc().isAfter(dayEnd))) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("minIntervalMinutes", minIntervalMinutes);
                detail.put("requiredShiftMinutes", shiftMinutes);
                detail.put("dayEndUtc", dayEnd.toString());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_UNSATISFIABLE",
                        "按最小间隔整体顺延至运营日结束仍无法避开限速", List.of(detail));
            }
            boolean stillViolates = shifted.stream()
                    .anyMatch(o -> !overlapping(active, o).isEmpty());
            if (!stillViolates) {
                return Optional.of(new ShiftPlan(shiftMinutes, shifted, impacts));
            }
        }
    }

    /**
     * 固化重排记录：追加头部与逐段明细（原计划时刻、新计划时刻、受影响分钟数、
     * 起决定作用的限速令版本），返回视图。记录不可变，后续撤销/修订限速令不改写。
     */
    public RearrangementView recordRearrangement(DayPlan plan, String opType, ShiftPlan shift,
                                                 String operator, long nowMillis) {
        long rearrangementId = rearrangementRepo.insertHeader(plan.id(), plan.scheduleKey(),
                opType, shift.shiftMinutes(), operator == null ? "" : operator, nowMillis);
        Map<Integer, Occupancy> shiftedBySeq = shift.shiftedOccupancies().stream()
                .collect(Collectors.toMap(Occupancy::seq, o -> o));
        List<RearrangementSegment> segments = shift.impacts().stream()
                .map(impact -> {
                    Occupancy old = impact.original();
                    Occupancy shifted = shiftedBySeq.get(old.seq());
                    WeatherRestriction binding = impact.binding();
                    return new RearrangementSegment(0L, rearrangementId, old.seq(), old.trainNo(),
                            old.sectionId(), old.startUtc(), old.endUtc(),
                            shifted.startUtc(), shifted.endUtc(), impact.affectedMinutes(),
                            binding.id(), binding.restrictionKey(), binding.version(),
                            binding.maxSpeedKmh());
                }).toList();
        rearrangementRepo.insertSegments(segments);
        return toView(new Rearrangement(rearrangementId, plan.id(), plan.scheduleKey(), opType,
                shift.shiftMinutes(), operator == null ? "" : operator, nowMillis));
    }

    // ---------- 内部实现 ----------

    private Occupancy shift(Occupancy o, long shiftMinutes) {
        Duration shift = Duration.ofMinutes(shiftMinutes);
        return new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                o.startUtc().plus(shift), o.endUtc().plus(shift));
    }

    private List<WeatherRestriction> overlapping(List<WeatherRestriction> restrictions, Occupancy o) {
        return restrictions.stream()
                .filter(r -> r.sectionId().equals(o.sectionId()))
                .filter(r -> r.startUtc().isBefore(o.endUtc()) && o.startUtc().isBefore(r.endUtc()))
                .toList();
    }

    /**
     * 起决定作用的限速令：速度最低者优先，并列时取主键较小者（裁决稳定）。
     */
    private WeatherRestriction binding(List<WeatherRestriction> hits) {
        return hits.stream()
                .min(Comparator.comparingInt(WeatherRestriction::maxSpeedKmh)
                        .thenComparingLong(WeatherRestriction::id))
                .orElseThrow();
    }

    /**
     * 占用与全部命中限速窗口的并集相交分钟数（左闭右开）。
     */
    private long unionOverlapMinutes(Occupancy o, List<WeatherRestriction> hits) {
        List<long[]> intervals = hits.stream()
                .map(r -> new long[]{
                        Math.max(o.startUtc().toEpochMilli(), r.startUtc().toEpochMilli()),
                        Math.min(o.endUtc().toEpochMilli(), r.endUtc().toEpochMilli())})
                .sorted(Comparator.comparingLong(i -> i[0]))
                .toList();
        long totalMillis = 0;
        long curStart = -1;
        long curEnd = -1;
        for (long[] interval : intervals) {
            if (interval[0] > curEnd) {
                if (curEnd > curStart) {
                    totalMillis += curEnd - curStart;
                }
                curStart = interval[0];
                curEnd = interval[1];
            } else {
                curEnd = Math.max(curEnd, interval[1]);
            }
        }
        if (curEnd > curStart) {
            totalMillis += curEnd - curStart;
        }
        return totalMillis / 60_000L;
    }

    private long overlapMillis(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        long start = Math.max(aStart.toEpochMilli(), bStart.toEpochMilli());
        long end = Math.min(aEnd.toEpochMilli(), bEnd.toEpochMilli());
        return Math.max(0L, end - start);
    }

    private void validateRestrictionParams(String sectionId, Instant startUtc, Instant endUtc,
                                           int maxSpeedKmh) {
        if (sectionId == null || sectionId.isBlank() || sectionId.length() > 64) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", "sectionId");
            detail.put("actual", sectionId == null ? null : sectionId.length());
            detail.put("required", "非空且长度 1～64");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_INVALID",
                    "区段非法", List.of(detail));
        }
        if (!endUtc.isAfter(startUtc)) {
            throw invalidWindow(startUtc, endUtc);
        }
        if (maxSpeedKmh < MIN_SPEED_KMH || maxSpeedKmh > MAX_SPEED_KMH) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", "maxSpeedKmh");
            detail.put("actualSpeedKmh", maxSpeedKmh);
            detail.put("minSpeedKmh", MIN_SPEED_KMH);
            detail.put("maxSpeedKmh", MAX_SPEED_KMH);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_INVALID",
                    "限速速度超出合法范围 " + MIN_SPEED_KMH + "～" + MAX_SPEED_KMH + " km/h: "
                            + maxSpeedKmh, List.of(detail));
        }
    }

    private ApiException invalidWindow(Instant startUtc, Instant endUtc) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", "endUtc");
        detail.put("actualStartUtc", startUtc.toString());
        detail.put("actualEndUtc", endUtc.toString());
        detail.put("required", "endUtc 必须晚于 startUtc（左闭右开）");
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_INVALID",
                "限速时段非法", List.of(detail));
    }

    private ApiException notFoundOrStateConflict(String restrictionKey) {
        if (restrictionRepo.findHistory(restrictionKey).isEmpty()) {
            return new ApiException(HttpStatus.NOT_FOUND, "RESTRICTION_NOT_FOUND",
                    "限速令不存在: " + restrictionKey);
        }
        return conflict("RESTRICTION_STATE_CONFLICT",
                "限速令无生效版本（已撤销）: " + restrictionKey);
    }

    private RestrictionResponse toResponse(WeatherRestriction r) {
        return new RestrictionResponse(r.restrictionKey(), r.version(), r.sectionId(),
                r.startUtc(), r.endUtc(), r.maxSpeedKmh(), r.status().name(), r.operator(),
                Instant.ofEpochMilli(r.createdAt()));
    }

    private RearrangementView toView(Rearrangement header) {
        List<RearrangementSegmentView> segments = rearrangementRepo.findSegments(header.id())
                .stream()
                .map(s -> new RearrangementSegmentView(s.seq(), s.trainNo(), s.sectionId(),
                        s.oldStartUtc(), s.oldEndUtc(), s.newStartUtc(), s.newEndUtc(),
                        s.affectedMinutes(), s.restrictionKey(), s.restrictionVersion(),
                        s.maxSpeedKmh()))
                .toList();
        return new RearrangementView(header.id(), header.opType(), header.shiftMinutes(), segments);
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<RestrictionResponse> replayIfPresent(String opType, String requestKey,
                                                          String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
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

    private RestrictionResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, RestrictionResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
