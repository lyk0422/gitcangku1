package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TrainProfile;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.ProfileRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.SectionProfileUpdateRequest;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import com.example.starter.plan.web.dto.TrainProfileUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 列车/区段制动档案配置：创建（版本 1）与带 expectedVersion 的修改。
 *
 * <p>档案修改与发布、改签共用全局发布锁，共同检查完整后态：若新参数使任一已发布
 * 带速占用的制动包络越限则整体 409，配置不生效。新增写入用 requestId 幂等，
 * 身份含操作、操作者、资源及全部参数和期望版本，以结构化 JSON 规范化后哈希，
 * 避免分隔符碰撞；同键同参重放首次完整响应，异参 409，失败不占键。
 */
@Service
public class ProfileService {

    private static final String OP_TRAIN_CREATE = "TRAIN_PROFILE_CREATE";
    private static final String OP_TRAIN_UPDATE = "TRAIN_PROFILE_UPDATE";
    private static final String OP_SECTION_CREATE = "SECTION_PROFILE_CREATE";
    private static final String OP_SECTION_UPDATE = "SECTION_PROFILE_UPDATE";

    private final ProfileRepository profileRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public ProfileService(ProfileRepository profileRepo, PlanRepository planRepo,
                          IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                          PlatformTransactionManager txManager) {
        this.profileRepo = profileRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建列车档案（版本 1）。
     */
    public TrainProfileResponse createTrainProfile(TrainProfileRequest req) {
        String hash = hashStructured(OP_TRAIN_CREATE, req.operator(), req.trainNo(), Map.of(
                "reactionTimeT", canonical(req.reactionTimeT()),
                "baseDecelA", canonical(req.baseDecelA())));
        Optional<TrainProfileResponse> replay =
                replayIfPresent(OP_TRAIN_CREATE, req.requestId(), hash, TrainProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (profileRepo.findTrainProfile(req.trainNo()).isPresent()) {
                    throw conflict("TRAIN_PROFILE_EXISTS", "列车档案已存在: " + req.trainNo());
                }
                long now = System.currentTimeMillis();
                profileRepo.insertTrainProfile(req.trainNo(), req.reactionTimeT(),
                        req.baseDecelA(), now);
                TrainProfileResponse response = new TrainProfileResponse(req.trainNo(),
                        req.reactionTimeT(), req.baseDecelA(), 1);
                idemRepo.insert(OP_TRAIN_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRAIN_CREATE, req.requestId(), hash, TrainProfileResponse.class)
                    .orElseThrow(() -> conflict("TRAIN_PROFILE_EXISTS",
                            "列车档案已存在: " + req.trainNo()));
        }
    }

    /**
     * 修改列车档案：全局发布锁内校验 expectedVersion 与全部已发布带速占用的后态包络，
     * 任一越限则 409 不生效；通过后版本加一。
     */
    public TrainProfileResponse updateTrainProfile(String trainNo, TrainProfileUpdateRequest req) {
        String hash = hashStructured(OP_TRAIN_UPDATE, req.operator(), trainNo, Map.of(
                "reactionTimeT", canonical(req.reactionTimeT()),
                "baseDecelA", canonical(req.baseDecelA()),
                "expectedVersion", String.valueOf(req.expectedVersion())));
        Optional<TrainProfileResponse> replay =
                replayIfPresent(OP_TRAIN_UPDATE, req.requestId(), hash, TrainProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                TrainProfile profile = profileRepo.findTrainProfileForUpdate(trainNo)
                        .orElseThrow(() -> notFound("TRAIN_PROFILE_NOT_FOUND",
                                "列车档案不存在: " + trainNo));
                if (req.expectedVersion() != profile.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前配置版本 " + profile.version() + " 不一致");
                }
                List<Map<String, Object>> violations = new ArrayList<>();
                for (Occupancy o : planRepo.findPublishedSpeedOccupanciesByTrain(trainNo)) {
                    Optional<SectionProfile> section =
                            profileRepo.findSectionProfile(o.sectionId());
                    if (section.isEmpty()) {
                        continue;
                    }
                    if (EnvelopeMath.exceeds(o.speedV(), req.reactionTimeT(), req.baseDecelA(),
                            section.get().protectD())) {
                        violations.add(detail(o, req.reactionTimeT(), req.baseDecelA(),
                                section.get().protectD()));
                    }
                }
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "ENVELOPE_CONFIG_CONFLICT",
                            "新参数使已发布占用制动包络越限，配置未生效", violations);
                }
                long now = System.currentTimeMillis();
                int newVersion = profile.version() + 1;
                profileRepo.updateTrainProfile(profile.id(), req.reactionTimeT(),
                        req.baseDecelA(), newVersion, now);
                TrainProfileResponse response = new TrainProfileResponse(trainNo,
                        req.reactionTimeT(), req.baseDecelA(), newVersion);
                idemRepo.insert(OP_TRAIN_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRAIN_UPDATE, req.requestId(), hash, TrainProfileResponse.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestId 已用于其他参数: " + req.requestId()));
        }
    }

    /**
     * 按列车编号查询档案，不存在返回 404。
     */
    public TrainProfileResponse getTrainProfile(String trainNo) {
        TrainProfile profile = profileRepo.findTrainProfile(trainNo)
                .orElseThrow(() -> notFound("TRAIN_PROFILE_NOT_FOUND", "列车档案不存在: " + trainNo));
        return new TrainProfileResponse(profile.trainNo(), profile.reactionT(),
                profile.baseDecelA(), profile.version());
    }

    /**
     * 创建区段档案（版本 1）。
     */
    public SectionProfileResponse createSectionProfile(SectionProfileRequest req) {
        String hash = hashStructured(OP_SECTION_CREATE, req.operator(), req.sectionId(), Map.of(
                "protectDistanceD", canonical(req.protectDistanceD())));
        Optional<SectionProfileResponse> replay = replayIfPresent(OP_SECTION_CREATE,
                req.requestId(), hash, SectionProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (profileRepo.findSectionProfile(req.sectionId()).isPresent()) {
                    throw conflict("SECTION_PROFILE_EXISTS", "区段档案已存在: " + req.sectionId());
                }
                long now = System.currentTimeMillis();
                profileRepo.insertSectionProfile(req.sectionId(), req.protectDistanceD(), now);
                SectionProfileResponse response = new SectionProfileResponse(req.sectionId(),
                        req.protectDistanceD(), 1);
                idemRepo.insert(OP_SECTION_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_CREATE, req.requestId(), hash,
                    SectionProfileResponse.class)
                    .orElseThrow(() -> conflict("SECTION_PROFILE_EXISTS",
                            "区段档案已存在: " + req.sectionId()));
        }
    }

    /**
     * 修改区段档案：全局发布锁内校验 expectedVersion 与全部已发布带速占用的后态包络，
     * 任一越限则 409 不生效；通过后版本加一。
     */
    public SectionProfileResponse updateSectionProfile(String sectionId,
                                                       SectionProfileUpdateRequest req) {
        String hash = hashStructured(OP_SECTION_UPDATE, req.operator(), sectionId, Map.of(
                "protectDistanceD", canonical(req.protectDistanceD()),
                "expectedVersion", String.valueOf(req.expectedVersion())));
        Optional<SectionProfileResponse> replay = replayIfPresent(OP_SECTION_UPDATE,
                req.requestId(), hash, SectionProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SectionProfile profile = profileRepo.findSectionProfileForUpdate(sectionId)
                        .orElseThrow(() -> notFound("SECTION_PROFILE_NOT_FOUND",
                                "区段档案不存在: " + sectionId));
                if (req.expectedVersion() != profile.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前配置版本 " + profile.version() + " 不一致");
                }
                List<Map<String, Object>> violations = new ArrayList<>();
                for (Occupancy o : planRepo.findPublishedSpeedOccupanciesBySection(sectionId)) {
                    Optional<TrainProfile> train = profileRepo.findTrainProfile(o.trainNo());
                    if (train.isEmpty()) {
                        continue;
                    }
                    if (EnvelopeMath.exceeds(o.speedV(), train.get().reactionT(),
                            train.get().baseDecelA(), req.protectDistanceD())) {
                        violations.add(detail(o, train.get().reactionT(),
                                train.get().baseDecelA(), req.protectDistanceD()));
                    }
                }
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "ENVELOPE_CONFIG_CONFLICT",
                            "新防护距离使已发布占用制动包络越限，配置未生效", violations);
                }
                long now = System.currentTimeMillis();
                int newVersion = profile.version() + 1;
                profileRepo.updateSectionProfile(profile.id(), req.protectDistanceD(),
                        newVersion, now);
                SectionProfileResponse response = new SectionProfileResponse(sectionId,
                        req.protectDistanceD(), newVersion);
                idemRepo.insert(OP_SECTION_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_UPDATE, req.requestId(), hash,
                    SectionProfileResponse.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestId 已用于其他参数: " + req.requestId()));
        }
    }

    /**
     * 按区段 ID 查询档案，不存在返回 404。
     */
    public SectionProfileResponse getSectionProfile(String sectionId) {
        SectionProfile profile = profileRepo.findSectionProfile(sectionId)
                .orElseThrow(() -> notFound("SECTION_PROFILE_NOT_FOUND",
                        "区段档案不存在: " + sectionId));
        return new SectionProfileResponse(profile.sectionId(), profile.protectD(),
                profile.version());
    }

    // ---------- 内部实现 ----------

    /**
     * 结构化越限明细：给出列车、区段、速度与精确判定依据。
     */
    private Map<String, Object> detail(Occupancy o, BigDecimal t, BigDecimal a, BigDecimal d) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "ENVELOPE_EXCEEDED");
        detail.put("trainNo", o.trainNo());
        detail.put("sectionId", o.sectionId());
        detail.put("speedV", o.speedV());
        detail.put("reactionTimeT", t);
        detail.put("baseDecelA", a);
        detail.put("protectDistanceD", d);
        detail.put("leftValue", EnvelopeMath.leftValue(o.speedV(), t, a));
        detail.put("rightValue", EnvelopeMath.rightValue(a, d));
        return detail;
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 结构化身份哈希：操作、操作者、资源及全部参数（含期望版本）按键序排入有序 Map，
     * 序列化为 JSON 后取 SHA-256，避免分隔符碰撞。
     */
    private String hashStructured(String opType, String operator, String resource,
                                  Map<String, String> params) {
        Map<String, String> identity = new TreeMap<>(params);
        identity.put("op", opType);
        identity.put("operator", operator);
        identity.put("resource", resource);
        return sha256(toJson(identity));
    }

    /**
     * 十进制参数规范化：去除尾部零，保证数值相等即身份相等。
     */
    private static String canonical(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
