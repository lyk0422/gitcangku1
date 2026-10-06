package com.example.starter.plan.service;

import com.example.starter.plan.model.PublishedSpeedSlot;
import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TrainProfile;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.ProfileRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
 * 列车/区段档案配置服务：版本从 1 递增，修改携带 expectedVersion 乐观校验；
 * 参数变更在发布锁内对已发布带速度占用逐项做完整后态校验，任一越限则 409；
 * 幂等身份为操作、操作者、资源与全部参数（含期望版本）的结构化 JSON 摘要，避免分隔符碰撞。
 */
@Service
public class ProfileService {

    private static final String OP_TRAIN_PROFILE = "TRAIN_PROFILE";
    private static final String OP_SECTION_PROFILE = "SECTION_PROFILE";

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
     * 创建或修改列车档案。expectedVersion 为 0 表示创建，大于 0 表示修改且须匹配当前版本。
     */
    public TrainProfileResponse upsertTrainProfile(String trainNo, TrainProfileRequest req) {
        String hash = hashTrain(trainNo, req);
        Optional<TrainProfileResponse> replay =
                replayIfPresent(OP_TRAIN_PROFILE, req.requestId(), hash, TrainProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<TrainProfile> existing = profileRepo.findTrainForUpdate(trainNo);
                long now = System.currentTimeMillis();
                if (req.expectedVersion() == 0) {
                    if (existing.isPresent()) {
                        throw conflict("VERSION_CONFLICT",
                                "档案已存在，创建须 expectedVersion=0: " + trainNo);
                    }
                    profileRepo.insertTrain(trainNo, req.reactionTime(), req.baseDeceleration(), now);
                } else {
                    TrainProfile current = existing.orElseThrow(() -> notFoundTrain(trainNo));
                    if (req.expectedVersion() != current.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与当前版本 " + current.version() + " 不一致");
                    }
                    validateTrainPostState(trainNo, req.reactionTime(), req.baseDeceleration());
                    profileRepo.updateTrain(current.id(), req.reactionTime(),
                            req.baseDeceleration(), current.version() + 1, now);
                }
                TrainProfile saved = profileRepo.findTrain(trainNo)
                        .orElseThrow(() -> new IllegalStateException("档案写入后读取失败: " + trainNo));
                TrainProfileResponse response = toResponse(saved);
                idemRepo.insert(OP_TRAIN_PROFILE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRAIN_PROFILE, req.requestId(), hash, TrainProfileResponse.class)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "列车档案已存在: " + trainNo));
        } catch (ApiException e) {
            // 并发下同键请求在锁后读到已存在档案时，以已提交的幂等记录重放首次结果
            return replayAfterConflict(OP_TRAIN_PROFILE, req.requestId(), hash, e,
                    TrainProfileResponse.class);
        }
    }

    /**
     * 创建或修改区段档案。expectedVersion 为 0 表示创建，大于 0 表示修改且须匹配当前版本。
     */
    public SectionProfileResponse upsertSectionProfile(String sectionId, SectionProfileRequest req) {
        String hash = hashSection(sectionId, req);
        Optional<SectionProfileResponse> replay = replayIfPresent(
                OP_SECTION_PROFILE, req.requestId(), hash, SectionProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Optional<SectionProfile> existing = profileRepo.findSectionForUpdate(sectionId);
                long now = System.currentTimeMillis();
                if (req.expectedVersion() == 0) {
                    if (existing.isPresent()) {
                        throw conflict("VERSION_CONFLICT",
                                "档案已存在，创建须 expectedVersion=0: " + sectionId);
                    }
                    profileRepo.insertSection(sectionId, req.protectionDistance(), now);
                } else {
                    SectionProfile current = existing.orElseThrow(() -> notFoundSection(sectionId));
                    if (req.expectedVersion() != current.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与当前版本 " + current.version() + " 不一致");
                    }
                    validateSectionPostState(sectionId, req.protectionDistance());
                    profileRepo.updateSection(current.id(), req.protectionDistance(),
                            current.version() + 1, now);
                }
                SectionProfile saved = profileRepo.findSection(sectionId)
                        .orElseThrow(() -> new IllegalStateException("档案写入后读取失败: " + sectionId));
                SectionProfileResponse response = toResponse(saved);
                idemRepo.insert(OP_SECTION_PROFILE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_PROFILE, req.requestId(), hash,
                            SectionProfileResponse.class)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "区段档案已存在: " + sectionId));
        } catch (ApiException e) {
            return replayAfterConflict(OP_SECTION_PROFILE, req.requestId(), hash, e,
                    SectionProfileResponse.class);
        }
    }

    /**
     * 查询列车档案，不存在返回 404。
     */
    public TrainProfileResponse getTrainProfile(String trainNo) {
        return toResponse(profileRepo.findTrain(trainNo).orElseThrow(() -> notFoundTrain(trainNo)));
    }

    /**
     * 查询区段档案，不存在返回 404。
     */
    public SectionProfileResponse getSectionProfile(String sectionId) {
        return toResponse(profileRepo.findSection(sectionId)
                .orElseThrow(() -> notFoundSection(sectionId)));
    }

    // ---------- 内部实现 ----------

    /**
     * 列车参数变更的完整后态校验：以候选 t、a 与当前区段 d 逐项检查全部已发布带速度占用，
     * 任一越限则 409，本次变更不生效。
     */
    private void validateTrainPostState(String trainNo, BigDecimal newT, BigDecimal newA) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (PublishedSpeedSlot slot : planRepo.findPublishedSpeedSlotsByTrain(trainNo)) {
            SectionProfile section = profileRepo.findSection(slot.sectionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "已发布占用缺少区段档案: " + slot.sectionId()));
            if (!EnvelopeMath.withinLimit(slot.speedMps(), newT, newA,
                    section.protectionDistanceM())) {
                violations.add(violationDetail(slot));
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ENVELOPE_WOULD_EXCEED",
                    "列车参数变更将使已发布占用越限: " + trainNo, violations);
        }
    }

    /**
     * 区段参数变更的完整后态校验：以候选 d 与当前列车 t、a 逐项检查全部已发布带速度占用，
     * 任一越限则 409，本次变更不生效。
     */
    private void validateSectionPostState(String sectionId, BigDecimal newD) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (PublishedSpeedSlot slot : planRepo.findPublishedSpeedSlotsBySection(sectionId)) {
            TrainProfile train = profileRepo.findTrain(slot.trainNo())
                    .orElseThrow(() -> new IllegalStateException(
                            "已发布占用缺少列车档案: " + slot.trainNo()));
            if (!EnvelopeMath.withinLimit(slot.speedMps(), train.reactionTimeS(),
                    train.baseDeceleration(), newD)) {
                violations.add(violationDetail(slot));
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ENVELOPE_WOULD_EXCEED",
                    "区段参数变更将使已发布占用越限: " + sectionId, violations);
        }
    }

    private Map<String, Object> violationDetail(PublishedSpeedSlot slot) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "ENVELOPE_WOULD_EXCEED");
        detail.put("scheduleKey", slot.scheduleKey());
        detail.put("trainNo", slot.trainNo());
        detail.put("sectionId", slot.sectionId());
        detail.put("speed", slot.speedMps().toPlainString());
        return detail;
    }

    private TrainProfileResponse toResponse(TrainProfile profile) {
        return new TrainProfileResponse(profile.trainNo(), profile.reactionTimeS(),
                profile.baseDeceleration(), profile.version());
    }

    private SectionProfileResponse toResponse(SectionProfile profile) {
        return new SectionProfileResponse(profile.sectionId(), profile.protectionDistanceM(),
                profile.version());
    }

    /**
     * 结构化身份摘要：操作、操作者、资源与全部参数（含期望版本）组成 JSON 后取 SHA-256，
     * 字段经 JSON 转义，不存在分隔符碰撞。
     */
    private String hashTrain(String trainNo, TrainProfileRequest req) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("op", OP_TRAIN_PROFILE);
        node.put("operator", req.operator());
        node.put("trainNo", trainNo);
        node.put("reactionTime", normalize(req.reactionTime()));
        node.put("baseDeceleration", normalize(req.baseDeceleration()));
        node.put("expectedVersion", req.expectedVersion());
        return sha256(node.toString());
    }

    private String hashSection(String sectionId, SectionProfileRequest req) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("op", OP_SECTION_PROFILE);
        node.put("operator", req.operator());
        node.put("sectionId", sectionId);
        node.put("protectionDistance", normalize(req.protectionDistance()));
        node.put("expectedVersion", req.expectedVersion());
        return sha256(node.toString());
    }

    private static String normalize(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 并发竞态裁决：本请求因 409 冲突失败时，若同键同参请求已在其间提交，
     * 重放首次成功结果；否则原样抛出。
     */
    private <T> T replayAfterConflict(String opType, String requestId, String hash,
                                      ApiException failure, Class<T> type) {
        if (failure.status() == HttpStatus.CONFLICT) {
            Optional<T> replayed = replayIfPresent(opType, requestId, hash, type);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }
        throw failure;
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

    private ApiException notFoundTrain(String trainNo) {
        return new ApiException(HttpStatus.NOT_FOUND, "TRAIN_PROFILE_NOT_FOUND",
                "列车档案不存在: " + trainNo);
    }

    private ApiException notFoundSection(String sectionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "SECTION_PROFILE_NOT_FOUND",
                "区段档案不存在: " + sectionId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
