package com.example.starter.plan.service;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.RestrictionActionRequest;
import com.example.starter.plan.web.dto.RestrictionCreateRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.RestrictionReviseRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 气象限速令核心服务：登记、修订、撤销与查询。
 *
 * <p>限速速度范围 10~300km/h，区段或时段非法返回 422；登记、修订、撤销与发布、改签
 * 经全局发布锁按事务提交顺序裁决。幂等约定与计划服务一致：同键同参重放首次完整结果，
 * 异参 409，失败不占键。撤销或修订不改写已生成的重排记录。
 */
@Service
public class RestrictionService {

    /** 限速最低速度（km/h，含）。 */
    public static final int MIN_SPEED_KMH = 10;

    /** 限速最高速度（km/h，含）。 */
    public static final int MAX_SPEED_KMH = 300;

    private static final String OP_REST_CREATE = "REST_CREATE";
    private static final String OP_REST_REVISE = "REST_REVISE";
    private static final String OP_REST_REVOKE = "REST_REVOKE";

    private final RestrictionRepository restrictionRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RestrictionService(RestrictionRepository restrictionRepo, PlanRepository planRepo,
                              IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                              PlatformTransactionManager txManager, Clock clock) {
        this.restrictionRepo = restrictionRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 登记限速令（版本 1，状态 ACTIVE）；同一区段同一时刻允许叠加多条，生效速度取最低值。
     */
    public RestrictionResponse create(RestrictionCreateRequest req) {
        validateSection(req.sectionId());
        validateWindow(req.startUtc(), req.endUtc());
        validateSpeed(req.maxSpeedKmh());
        String hash = sha256(OP_REST_CREATE + '\n' + req.restrictionKey() + '\n' + req.sectionId()
                + '\n' + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REST_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                if (restrictionRepo.findByKey(req.restrictionKey()).isPresent()) {
                    throw conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在: " + req.restrictionKey());
                }
                long now = clock.millis();
                restrictionRepo.insert(req.restrictionKey(), req.sectionId(), req.startUtc(),
                        req.endUtc(), req.maxSpeedKmh(), req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(req.restrictionKey()).orElseThrow());
                idemRepo.insert(OP_REST_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REST_CREATE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在: " + req.restrictionKey()));
        }
    }

    /**
     * 修订限速令时段与最高速度，版本加一；仅 ACTIVE 可修订，expectedVersion 必须匹配。
     */
    public RestrictionResponse revise(String restrictionKey, RestrictionReviseRequest req) {
        validateWindow(req.startUtc(), req.endUtc());
        validateSpeed(req.maxSpeedKmh());
        String hash = sha256(OP_REST_REVISE + '\n' + restrictionKey + '\n' + req.expectedVersion()
                + '\n' + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REST_REVISE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SpeedRestriction current = restrictionRepo.findByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFound(restrictionKey));
                if (current.status() != RestrictionStatus.ACTIVE) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "仅生效限速令可修订，当前状态: " + current.status());
                }
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
                long now = clock.millis();
                restrictionRepo.revise(current.id(), current.version() + 1, req.startUtc(),
                        req.endUtc(), req.maxSpeedKmh(), req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_REST_REVISE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REST_REVISE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT", "限速令修订冲突"));
        }
    }

    /**
     * 撤销限速令：状态置为 REVOKED，历史保留，不再影响后续发布或改签；仅 ACTIVE 可撤销。
     */
    public RestrictionResponse revoke(String restrictionKey, RestrictionActionRequest req) {
        String hash = sha256(OP_REST_REVOKE + '\n' + restrictionKey + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REST_REVOKE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SpeedRestriction current = restrictionRepo.findByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFound(restrictionKey));
                if (current.status() != RestrictionStatus.ACTIVE) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "仅生效限速令可撤销，当前状态: " + current.status());
                }
                long now = clock.millis();
                restrictionRepo.revoke(current.id(), req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_REST_REVOKE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REST_REVOKE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT", "限速令撤销冲突"));
        }
    }

    /**
     * 按业务键查询限速令明细（只读），不存在返回 404。
     */
    public RestrictionResponse getRestriction(String restrictionKey) {
        return toResponse(restrictionRepo.findByKey(restrictionKey)
                .orElseThrow(() -> notFound(restrictionKey)));
    }

    /**
     * 查询限速令列表（只读），sectionId 非空时按区段过滤。
     */
    public List<RestrictionResponse> listRestrictions(String sectionId) {
        return restrictionRepo.findAll(sectionId).stream().map(this::toResponse).toList();
    }

    // ---------- 内部实现 ----------

    private RestrictionResponse toResponse(SpeedRestriction r) {
        return new RestrictionResponse(r.restrictionKey(), r.sectionId(), r.startUtc(),
                r.endUtc(), r.maxSpeedKmh(), r.version(), r.status().name(), r.operator(),
                r.createdAt(), r.updatedAt());
    }

    /**
     * 区段非法返回 422（空或空白）。
     */
    private void validateSection(String sectionId) {
        if (sectionId == null || sectionId.isBlank()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", "sectionId");
            detail.put("actual", sectionId);
            detail.put("required", "非空区段 ID");
            throw unprocessable("INVALID_SECTION", "区段非法: sectionId 不能为空", detail);
        }
    }

    /**
     * 时段非法返回 422：必须为左闭右开有效区间，即 endUtc 晚于 startUtc。
     */
    private void validateWindow(Instant startUtc, Instant endUtc) {
        if (startUtc == null || endUtc == null || !endUtc.isAfter(startUtc)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("actualStartUtc", startUtc == null ? null : startUtc.toString());
            detail.put("actualEndUtc", endUtc == null ? null : endUtc.toString());
            detail.put("required", "endUtc 必须晚于 startUtc（左闭右开）");
            throw unprocessable("INVALID_TIME_WINDOW", "时段非法: endUtc 必须晚于 startUtc", detail);
        }
    }

    /**
     * 速度非法返回 422：合法范围 10~300km/h，携带实际值与要求范围。
     */
    private void validateSpeed(Integer maxSpeedKmh) {
        if (maxSpeedKmh == null || maxSpeedKmh < MIN_SPEED_KMH || maxSpeedKmh > MAX_SPEED_KMH) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", "maxSpeedKmh");
            detail.put("actual", maxSpeedKmh);
            detail.put("requiredMin", MIN_SPEED_KMH);
            detail.put("requiredMax", MAX_SPEED_KMH);
            throw unprocessable("INVALID_SPEED_RANGE",
                    "限速速度须为 10~300km/h，实际: " + maxSpeedKmh, detail);
        }
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<RestrictionResponse> replayIfPresent(String opType, String requestKey,
                                                          String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestKey 已用于其他参数: " + requestKey);
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

    private ApiException unprocessable(String code, String message, Map<String, Object> detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, List.of(detail));
    }

    private ApiException notFound(String restrictionKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESTRICTION_NOT_FOUND",
                "限速令不存在: " + restrictionKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}