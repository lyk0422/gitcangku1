package com.example.starter.plan.service;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.AmendRestrictionRequest;
import com.example.starter.plan.web.dto.EffectiveSpeedView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.RevokeRestrictionRequest;
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
 * 限速令登记、修订、撤销与查询诊断。写操作与发布/改签共用全局互斥锁，
 * 按事务提交顺序裁决；按 (操作类型, requestKey) 幂等，指纹包含参数与操作者，
 * 同键同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class RestrictionService {

    /** 最高速度下限（km/h，含）。 */
    public static final int MIN_SPEED_KMH = 10;
    /** 最高速度上限（km/h，含）。 */
    public static final int MAX_SPEED_KMH = 300;

    private static final String OP_RST_CREATE = "RST_CREATE";
    private static final String OP_RST_AMEND = "RST_AMEND";
    private static final String OP_RST_REVOKE = "RST_REVOKE";

    private final RestrictionRepository restrictionRepo;
    private final IdempotencyRepository idemRepo;
    private final PlanRepository planRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RestrictionService(RestrictionRepository restrictionRepo,
                              IdempotencyRepository idemRepo, PlanRepository planRepo,
                              ObjectMapper objectMapper, PlatformTransactionManager txManager,
                              Clock clock) {
        this.restrictionRepo = restrictionRepo;
        this.idemRepo = idemRepo;
        this.planRepo = planRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * 登记限速令（版本 1，状态 ACTIVE）。区段或时段非法返回 422。
     */
    public RestrictionResponse register(RegisterRestrictionRequest req) {
        validateParams(req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh());
        String hash = sha256(OP_RST_CREATE + '\n' + req.restrictionKey() + '\n' + req.sectionId()
                + '\n' + req.startUtc() + '\n' + req.endUtc() + '\n' + req.maxSpeedKmh()
                + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_RST_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复查幂等：并发同键请求等待锁期间首次结果已提交，直接重放
                Optional<RestrictionResponse> replayInTx =
                        replayIfPresent(OP_RST_CREATE, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                if (restrictionRepo.findByKey(req.restrictionKey()).isPresent()) {
                    throw conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在: " + req.restrictionKey());
                }
                long now = clock.millis();
                SpeedRestriction restriction = new SpeedRestriction(0L, req.restrictionKey(), 1,
                        req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh(),
                        RestrictionStatus.ACTIVE, req.operator(), now, now, null);
                restrictionRepo.insert(restriction);
                RestrictionResponse response = toResponse(restriction);
                idemRepo.insert(OP_RST_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RST_CREATE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_KEY_EXISTS", "restrictionKey 已存在"));
        }
    }

    /**
     * 修订限速令：expectedVersion 必须匹配，成功版本加一；已撤销不可修订。
     * 修订不改写已生成的重排记录。
     */
    public RestrictionResponse amend(String restrictionKey, AmendRestrictionRequest req) {
        validateParams(req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh());
        String hash = sha256(OP_RST_AMEND + '\n' + restrictionKey + '\n' + req.expectedVersion()
                + '\n' + req.sectionId() + '\n' + req.startUtc() + '\n' + req.endUtc()
                + '\n' + req.maxSpeedKmh() + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_RST_AMEND, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<RestrictionResponse> replayInTx =
                        replayIfPresent(OP_RST_AMEND, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                SpeedRestriction current = restrictionRepo.findByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFound(restrictionKey));
                if (current.status() != RestrictionStatus.ACTIVE) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "仅生效中限速令可修订，当前状态: " + current.status());
                }
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
                long now = clock.millis();
                restrictionRepo.updateAmendment(current.id(), current.version() + 1,
                        req.sectionId(), req.startUtc(), req.endUtc(), req.maxSpeedKmh(),
                        req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_RST_AMEND, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RST_AMEND, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_KEY_EXISTS", "限速令并发修订冲突"));
        }
    }

    /**
     * 撤销限速令：状态置 REVOKED，不再影响后续发布或改签；历史与重排记录保留。
     */
    public RestrictionResponse revoke(String restrictionKey, RevokeRestrictionRequest req) {
        String hash = sha256(OP_RST_REVOKE + '\n' + restrictionKey + '\n' + req.operator());
        Optional<RestrictionResponse> replay = replayIfPresent(OP_RST_REVOKE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<RestrictionResponse> replayInTx =
                        replayIfPresent(OP_RST_REVOKE, req.requestKey(), hash);
                if (replayInTx.isPresent()) {
                    return replayInTx.get();
                }
                SpeedRestriction current = restrictionRepo.findByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFound(restrictionKey));
                if (current.status() != RestrictionStatus.ACTIVE) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令已撤销: " + restrictionKey);
                }
                long now = clock.millis();
                restrictionRepo.markRevoked(current.id(), req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_RST_REVOKE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_RST_REVOKE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT", "限速令并发撤销冲突"));
        }
    }

    /**
     * 限速令明细（只读，不改变状态）。
     */
    public RestrictionResponse getRestriction(String restrictionKey) {
        return restrictionRepo.findByKey(restrictionKey)
                .map(this::toResponse)
                .orElseThrow(() -> notFound(restrictionKey));
    }

    /**
     * 限速令历史清单（含已撤销），可按区段过滤（只读）。
     */
    public List<RestrictionResponse> listRestrictions(Optional<String> sectionId) {
        return restrictionRepo.findAll(sectionId).stream().map(this::toResponse).toList();
    }

    /**
     * 诊断：区段在指定时刻的生效限速（叠加以最低速度为准）。
     * 无生效限速令时 effectiveSpeedKmh 为 null（只读）。
     */
    public EffectiveSpeedView getEffectiveSpeed(String sectionId, Instant at) {
        List<SpeedRestriction> active = restrictionRepo.findActiveAt(sectionId, at);
        Integer effective = active.stream()
                .map(SpeedRestriction::maxSpeedKmh).min(Integer::compareTo).orElse(null);
        return new EffectiveSpeedView(sectionId, at, effective,
                active.stream().map(this::toResponse).toList());
    }

    // ---------- 内部实现 ----------

    /**
     * 参数级校验：区段、时段与速度非法均返回 422，明细携带实际值与要求值。
     */
    private void validateParams(String sectionId, Instant startUtc, Instant endUtc,
                                Integer maxSpeedKmh) {
        if (sectionId == null || sectionId.isBlank()) {
            throw invalid("sectionId", String.valueOf(sectionId), "非空区段 ID");
        }
        if (startUtc == null || endUtc == null) {
            throw invalid("timeRange", "startUtc=" + startUtc + ", endUtc=" + endUtc,
                    "起止时刻均须为非空 UTC 时刻");
        }
        if (!endUtc.isAfter(startUtc)) {
            throw invalid("endUtc", "startUtc=" + startUtc + ", endUtc=" + endUtc,
                    "endUtc 必须晚于 startUtc（左闭右开）");
        }
        if (maxSpeedKmh == null || maxSpeedKmh < MIN_SPEED_KMH || maxSpeedKmh > MAX_SPEED_KMH) {
            throw invalid("maxSpeedKmh", String.valueOf(maxSpeedKmh),
                    "取值范围 " + MIN_SPEED_KMH + "～" + MAX_SPEED_KMH + " km/h");
        }
    }

    private ApiException invalid(String field, String actual, String required) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("field", field);
        detail.put("actual", actual);
        detail.put("required", required);
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESTRICTION",
                "限速令参数非法: " + field, List.of(detail));
    }

    private RestrictionResponse toResponse(SpeedRestriction r) {
        return new RestrictionResponse(r.restrictionKey(), r.version(), r.sectionId(),
                r.startUtc(), r.endUtc(), r.maxSpeedKmh(), r.status().name(), r.operator(),
                r.createdAt(), r.updatedAt(), r.revokedAt());
    }

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

    private ApiException notFound(String restrictionKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESTRICTION_NOT_FOUND",
                "限速令不存在: " + restrictionKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
