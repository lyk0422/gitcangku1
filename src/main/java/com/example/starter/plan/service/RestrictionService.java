package com.example.starter.plan.service;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.EffectiveLimitView;
import com.example.starter.plan.web.dto.RestrictionActionRequest;
import com.example.starter.plan.web.dto.RestrictionRegisterRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.RestrictionReviseRequest;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
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
 * 气象限速令与区段档案业务：登记、撤销、修订与查询。
 *
 * <p>校验规则：区段必须已登记档案、时段必须 end 晚于 start（UTC 左闭右开）、
 * 最高速度必须在 10～300 km/h，违反任一返回 422 并携带实际值与要求值。
 * 写操作按 (操作类型, requestKey) 幂等，指纹含限速参数与操作者；同键同参重放首次结果，
 * 同键异参 409，失败不占键。撤销与修订经行锁按事务提交顺序生效；已生成的重排记录不受改写。
 */
@Service
public class RestrictionService {

    /** 限速最高速度合法下限 km/h。 */
    public static final int MIN_SPEED_KMH = 10;
    /** 限速最高速度合法上限 km/h。 */
    public static final int MAX_SPEED_KMH = 300;

    private static final String OP_REGISTER = "RST_REGISTER";
    private static final String OP_REVOKE = "RST_REVOKE";
    private static final String OP_REVISE = "RST_REVISE";

    private final RestrictionRepository restrictionRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public RestrictionService(RestrictionRepository restrictionRepo, IdempotencyRepository idemRepo,
                              ObjectMapper objectMapper, Clock clock,
                              PlatformTransactionManager txManager) {
        this.restrictionRepo = restrictionRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记区段档案；区段已存在返回 409。
     */
    public SectionProfileResponse registerSection(SectionProfileRequest req) {
        try {
            return tx.execute(status -> {
                SectionProfile profile = new SectionProfile(req.sectionId(), req.designSpeedKmh(),
                        req.minHeadwayMinutes());
                restrictionRepo.insertSection(profile, clock.millis());
                return new SectionProfileResponse(profile.sectionId(), profile.designSpeedKmh(),
                        profile.minHeadwayMinutes());
            });
        } catch (DuplicateKeyException e) {
            throw conflict("SECTION_EXISTS", "区段档案已存在: " + req.sectionId());
        }
    }

    /**
     * 查询区段档案，不存在返回 404。
     */
    public SectionProfileResponse getSection(String sectionId) {
        SectionProfile profile = restrictionRepo.findSection(sectionId)
                .orElseThrow(() -> notFound("SECTION_NOT_FOUND", "区段档案不存在: " + sectionId));
        return new SectionProfileResponse(profile.sectionId(), profile.designSpeedKmh(),
                profile.minHeadwayMinutes());
    }

    /**
     * 登记限速令（版本 1，状态 ACTIVE）。区段未登记、时段非法、速度越界均返回 422。
     */
    public RestrictionResponse register(RestrictionRegisterRequest req) {
        validateWindow(req.startUtc(), req.endUtc());
        validateSpeed(req.maxSpeedKmh());
        String hash = sha256(OP_REGISTER + '\n' + req.restrictionKey() + '\n' + req.sectionId()
                + '\n' + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + operator(req.operator()));
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REGISTER, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (restrictionRepo.findSection(req.sectionId()).isEmpty()) {
                    throw unprocessable("INVALID_SECTION", "区段未登记档案: " + req.sectionId(),
                            detail("sectionId", req.sectionId()));
                }
                if (restrictionRepo.findByKey(req.restrictionKey()).isPresent()) {
                    throw conflict("RESTRICTION_KEY_EXISTS", "restrictionKey 已存在: "
                            + req.restrictionKey());
                }
                long now = clock.millis();
                restrictionRepo.insertRestriction(req.restrictionKey(), req.sectionId(),
                        req.startUtc(), req.endUtc(), req.maxSpeedKmh(), req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(req.restrictionKey()).orElseThrow());
                idemRepo.insert(OP_REGISTER, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REGISTER, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_KEY_EXISTS",
                            "restrictionKey 已存在: " + req.restrictionKey()));
        }
    }

    /**
     * 撤销限速令：状态置为 REVOKED，不再影响后续发布或改签；已生成的重排记录不改写。
     */
    public RestrictionResponse revoke(String restrictionKey, RestrictionActionRequest req) {
        String hash = sha256(OP_REVOKE + '\n' + restrictionKey + '\n' + operator(req.operator()));
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REVOKE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                SpeedRestriction restriction = restrictionRepo.findByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFound("RESTRICTION_NOT_FOUND",
                                "限速令不存在: " + restrictionKey));
                if (restriction.status() == RestrictionStatus.REVOKED) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令已撤销: " + restrictionKey);
                }
                long now = clock.millis();
                restrictionRepo.revokeRestriction(restriction.id(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_REVOKE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REVOKE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令已撤销: " + restrictionKey));
        }
    }

    /**
     * 修订限速令：调整时段与最高速度，版本加一；已撤销不可修订；重排记录保持修订前快照。
     */
    public RestrictionResponse revise(String restrictionKey, RestrictionReviseRequest req) {
        validateWindow(req.startUtc(), req.endUtc());
        validateSpeed(req.maxSpeedKmh());
        String hash = sha256(OP_REVISE + '\n' + restrictionKey + '\n'
                + req.startUtc().toEpochMilli() + '\n' + req.endUtc().toEpochMilli()
                + '\n' + req.maxSpeedKmh() + '\n' + operator(req.operator()));
        Optional<RestrictionResponse> replay = replayIfPresent(OP_REVISE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                SpeedRestriction restriction = restrictionRepo.findByKeyForUpdate(restrictionKey)
                        .orElseThrow(() -> notFound("RESTRICTION_NOT_FOUND",
                                "限速令不存在: " + restrictionKey));
                if (restriction.status() == RestrictionStatus.REVOKED) {
                    throw conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令已撤销，不可修订: " + restrictionKey);
                }
                long now = clock.millis();
                restrictionRepo.reviseRestriction(restriction.id(), req.startUtc(), req.endUtc(),
                        req.maxSpeedKmh(), restriction.version() + 1, req.operator(), now);
                RestrictionResponse response = toResponse(
                        restrictionRepo.findByKey(restrictionKey).orElseThrow());
                idemRepo.insert(OP_REVISE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_REVISE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("RESTRICTION_STATE_CONFLICT",
                            "限速令状态冲突: " + restrictionKey));
        }
    }

    /**
     * 查询限速令明细，不存在返回 404。
     */
    public RestrictionResponse getRestriction(String restrictionKey) {
        return toResponse(restrictionRepo.findByKey(restrictionKey)
                .orElseThrow(() -> notFound("RESTRICTION_NOT_FOUND", "限速令不存在: " + restrictionKey)));
    }

    /**
     * 查询限速令历史列表；sectionId 为空时返回全部，按业务键排序。读取不改变状态。
     */
    public List<RestrictionResponse> listRestrictions(String sectionId) {
        List<SpeedRestriction> restrictions = sectionId == null || sectionId.isBlank()
                ? restrictionRepo.findAll() : restrictionRepo.findBySection(sectionId);
        return restrictions.stream().map(this::toResponse).toList();
    }

    /**
     * 诊断：区段在某 UTC 时刻的生效限速（多条叠加以最低速度为准）。
     * effectiveSpeedKmh 为 null 表示该时刻无生效限速。区段未登记返回 404。
     */
    public EffectiveLimitView effectiveLimit(String sectionId, Instant at) {
        if (restrictionRepo.findSection(sectionId).isEmpty()) {
            throw notFound("SECTION_NOT_FOUND", "区段档案不存在: " + sectionId);
        }
        List<SpeedRestriction> active = restrictionRepo.findActiveAt(sectionId, at);
        Integer effective = active.stream().map(SpeedRestriction::maxSpeedKmh)
                .min(Integer::compareTo).orElse(null);
        return new EffectiveLimitView(sectionId, at, effective,
                active.stream().map(this::toResponse).toList());
    }

    // ---------- 内部实现 ----------

    private RestrictionResponse toResponse(SpeedRestriction r) {
        return new RestrictionResponse(r.restrictionKey(), r.sectionId(), r.startUtc(), r.endUtc(),
                r.maxSpeedKmh(), r.version(), r.status().name(), r.operator());
    }

    /**
     * 时段校验：end 必须晚于 start（UTC 左闭右开），违反返回 422 并携带实际值。
     */
    private void validateWindow(Instant startUtc, Instant endUtc) {
        if (!endUtc.isAfter(startUtc)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "INVALID_TIME_WINDOW");
            detail.put("actualStartUtc", startUtc.toString());
            detail.put("actualEndUtc", endUtc.toString());
            detail.put("required", "endUtc 必须晚于 startUtc（左闭右开）");
            throw unprocessable("INVALID_TIME_WINDOW", "限速时段非法: endUtc 必须晚于 startUtc", detail);
        }
    }

    /**
     * 速度校验：最高速度必须在 10～300 km/h，违反返回 422 并携带实际值与要求范围。
     */
    private void validateSpeed(int maxSpeedKmh) {
        if (maxSpeedKmh < MIN_SPEED_KMH || maxSpeedKmh > MAX_SPEED_KMH) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "INVALID_SPEED");
            detail.put("actualSpeedKmh", maxSpeedKmh);
            detail.put("requiredMinSpeedKmh", MIN_SPEED_KMH);
            detail.put("requiredMaxSpeedKmh", MAX_SPEED_KMH);
            throw unprocessable("INVALID_SPEED",
                    "限速速度非法: " + maxSpeedKmh + " km/h，合法范围 10～300", detail);
        }
    }

    private Map<String, Object> detail(String key, Object value) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "INVALID_SECTION");
        detail.put(key, value);
        return detail;
    }

    private String operator(String operator) {
        return operator == null ? "" : operator;
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

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
