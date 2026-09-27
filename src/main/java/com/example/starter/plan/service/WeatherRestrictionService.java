package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanRearrangement;
import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RearrangementRepository;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.OccupancyView;
import com.example.starter.plan.web.dto.RearrangementView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionImpactView;
import com.example.starter.plan.web.dto.RestrictionSnapshot;
import com.example.starter.plan.web.dto.RestrictionView;
import com.example.starter.plan.web.dto.RevokeRestrictionRequest;
import com.example.starter.plan.web.dto.SegmentImpactView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 铁路气象限速能力：限速令登记/撤销/明细查询、计划重排历史查询与限速影响诊断。
 *
 * <p>并发与幂等约定与计划写操作一致：登记与撤销在发布全局锁内按事务提交顺序裁决；
 * 按 (操作类型, requestKey) 幂等，同键同参重放首次成功结果，同键不同参 409，失败不占键。
 * 所有查询只读，不改变任何状态。
 */
@Service
public class WeatherRestrictionService {

    /** 运营日解释时区（与 PlanService 一致）。 */
    private static final ZoneId OPERATION_ZONE = ZoneId.of("Asia/Shanghai");

    /** 限速速度下限，单位 km/h。 */
    public static final int MIN_SPEED_KMH = 10;

    /** 限速速度上限，单位 km/h。 */
    public static final int MAX_SPEED_KMH = 300;

    private static final String OP_REGISTER = "RESTRICTION_REGISTER";
    private static final String OP_REVOKE = "RESTRICTION_REVOKE";

    private final RestrictionRepository restrictionRepo;
    private final RearrangementRepository rearrangementRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final RearrangementPlanner planner;
    private final RailLineProperties line;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WeatherRestrictionService(RestrictionRepository restrictionRepo,
                                     RearrangementRepository rearrangementRepo,
                                     PlanRepository planRepo,
                                     IdempotencyRepository idemRepo,
                                     RearrangementPlanner planner,
                                     RailLineProperties line,
                                     ObjectMapper objectMapper,
                                     Clock clock,
                                     PlatformTransactionManager txManager) {
        this.restrictionRepo = restrictionRepo;
        this.rearrangementRepo = rearrangementRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.planner = planner;
        this.line = line;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记限速令：区段、时段、速度非法返回 422；同区段同时段允许叠加多条，裁决以最低速度为准。
     */
    public RestrictionView register(RegisterRestrictionRequest req) {
        validateRegister(req);
        String hash = sha256(OP_REGISTER + '\n' + req.sectionId() + '\n'
                + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli() + '\n'
                + req.maxSpeedKmh() + '\n' + String.valueOf(req.operator()));
        Optional<RestrictionView> replay = replayIfPresent(OP_REGISTER, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                // 与发布/改签共用全局锁，保证登记、撤销、发布、改签按事务提交顺序裁决
                planRepo.acquirePublishLock();
                long now = clock.millis();
                long id = restrictionRepo.insert(req.sectionId(), req.startUtc(), req.endUtc(),
                        req.maxSpeedKmh(), req.operator(), now);
                RestrictionView view = toView(restrictionRepo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("限速令登记后读取缺失: " + id)));
                idemRepo.insert(OP_REGISTER, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REGISTER, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestKey 并发冲突: " + req.requestKey()));
        }
    }

    /**
     * 撤销限速令：仅影响后续发布或改签，不改写已生成的重排记录；重复撤销返回 409。
     */
    public RestrictionView revoke(long id, RevokeRestrictionRequest req) {
        String hash = sha256(OP_REVOKE + '\n' + id + '\n' + String.valueOf(req.operator()));
        Optional<RestrictionView> replay = replayIfPresent(OP_REVOKE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SpeedRestriction restriction = restrictionRepo.findByIdForUpdate(id)
                        .orElseThrow(() -> notFound(id));
                if (restriction.status() != RestrictionStatus.ACTIVE) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令已撤销，当前状态: " + restriction.status());
                }
                long now = clock.millis();
                restrictionRepo.markRevoked(id, now);
                RestrictionView view = toView(restrictionRepo.findById(id)
                        .orElseThrow(() -> new IllegalStateException("限速令撤销后读取缺失: " + id)));
                idemRepo.insert(OP_REVOKE, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REVOKE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestKey 并发冲突: " + req.requestKey()));
        }
    }

    /**
     * 限速令明细查询：按区段（可空）与是否包含已撤销过滤，排序稳定，只读。
     */
    public List<RestrictionView> listRestrictions(String sectionId, boolean includeRevoked) {
        return restrictionRepo.findAll(sectionId, includeRevoked).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 计划重排历史查询：按追加顺序返回不可变重排记录；计划不存在返回 404。只读。
     */
    public List<RearrangementView> getRearrangements(String scheduleKey) {
        if (planRepo.findByKey(scheduleKey).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "计划不存在: " + scheduleKey);
        }
        return rearrangementRepo.findByScheduleKey(scheduleKey).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 限速影响诊断：按计划当前占用与当前生效限速令，计算逐段受影响分钟数与所需整体顺延。
     * 只读，不改变任何状态；计划不存在返回 404。
     */
    public RestrictionImpactView getImpact(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId).distinct().sorted().toList();
        List<SpeedRestriction> restrictions = restrictionRepo.findActiveBySections(sectionIds);
        Instant dayEnd = plan.opDate().plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        List<SegmentImpactView> impacts = planner.impactsAt(occupancies, restrictions);
        boolean violation = impacts.stream().anyMatch(i -> i.deficitMinutes() > 0);
        boolean feasible = true;
        Integer requiredShiftMinutes = 0;
        if (violation) {
            try {
                requiredShiftMinutes = planner.compute(occupancies, restrictions, dayEnd).shiftMinutes();
            } catch (ApiException e) {
                feasible = false;
                requiredShiftMinutes = null;
            }
        }
        return new RestrictionImpactView(scheduleKey, line.normalSpeedKmh(),
                line.minIntervalMinutes(), violation, feasible, requiredShiftMinutes, impacts);
    }

    // ---------- 内部实现 ----------

    private void validateRegister(RegisterRestrictionRequest req) {
        if (req.sectionId() == null || req.sectionId().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SECTION",
                    "区段非法: sectionId 不能为空");
        }
        if (!req.endUtc().isAfter(req.startUtc())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INTERVAL",
                    "时段非法: endUtc 必须晚于 startUtc（左闭右开）");
        }
        int speed = req.maxSpeedKmh();
        if (speed < MIN_SPEED_KMH || speed > MAX_SPEED_KMH) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_SPEED",
                    "限速速度非法: " + speed + "，允许范围 " + MIN_SPEED_KMH + "～" + MAX_SPEED_KMH
                            + " km/h");
        }
    }

    private RestrictionView toView(SpeedRestriction r) {
        return new RestrictionView(r.id(), r.sectionId(), r.startUtc(), r.endUtc(),
                r.maxSpeedKmh(), r.status().name(), r.version(), r.operator(),
                r.createdAt(), r.revokedAt());
    }

    private RearrangementView toView(PlanRearrangement record) {
        return new RearrangementView(record.id(), record.scheduleKey(), record.opType(),
                record.shiftMinutes(),
                fromJson(record.restrictionSnapshotJson(), new TypeReference<>() {
                }),
                fromJson(record.originalJson(), new TypeReference<>() {
                }),
                fromJson(record.shiftedJson(), new TypeReference<>() {
                }),
                Instant.ofEpochMilli(record.createdAtMillis()));
    }

    /**
     * 构造限速令版本快照（固化业务证据）。
     */
    public static List<RestrictionSnapshot> snapshotsOf(List<SpeedRestriction> restrictions) {
        return restrictions.stream()
                .map(r -> new RestrictionSnapshot(r.id(), r.version(), r.sectionId(),
                        r.startUtc(), r.endUtc(), r.maxSpeedKmh()))
                .toList();
    }

    private Optional<RestrictionView> replayIfPresent(String opType, String requestKey, String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), new TypeReference<>() {
            });
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException notFound(long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESTRICTION_NOT_FOUND", "限速令不存在: " + id);
    }

    // 供 PlanService 复用的视图映射（占用清单快照）
    static List<OccupancyView> occupancyViews(List<Occupancy> occupancies) {
        return occupancies.stream()
                .map(o -> new OccupancyView(o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc()))
                .toList();
    }
}
