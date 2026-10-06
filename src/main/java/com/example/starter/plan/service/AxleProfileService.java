package com.example.starter.plan.service;

import com.example.starter.plan.model.SectionAxleLimit;
import com.example.starter.plan.model.TrainAxleProfile;
import com.example.starter.plan.repo.AxleProfileRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionAxleLimitCreateRequest;
import com.example.starter.plan.web.dto.SectionAxleLimitResponse;
import com.example.starter.plan.web.dto.SectionAxleLimitUpdateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileResponse;
import com.example.starter.plan.web.dto.TrainAxleProfileUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 列车轴重档案与区段轴限档案的登记、整体替换与查询。
 *
 * <p>幂等约定与计划写操作一致：按 (操作类型, requestId) 幂等，请求哈希覆盖操作、
 * 资源、操作者、全参数与期望版本（数值按值、文本原样）；同键同参重放返回首次结果，
 * 同键异参 409；失败不占键；业务与去重同事务，竞争不泄漏唯一键异常。
 * 档案写操作与发布/改签共用全局发布锁，保证核验的是同一完整后态。
 */
@Service
public class AxleProfileService {

    private static final String OP_TRAIN_CREATE = "TRAIN_PROFILE_CREATE";
    private static final String OP_TRAIN_UPDATE = "TRAIN_PROFILE_UPDATE";
    private static final String OP_SECTION_CREATE = "SECTION_LIMIT_CREATE";
    private static final String OP_SECTION_UPDATE = "SECTION_LIMIT_UPDATE";

    private final AxleProfileRepository axleRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final AxleEnvelopeService envelopeService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AxleProfileService(AxleProfileRepository axleRepo, PlanRepository planRepo,
                              IdempotencyRepository idemRepo, AxleEnvelopeService envelopeService,
                              ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.axleRepo = axleRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.envelopeService = envelopeService;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记列车轴重档案（版本 1）。列车编号已存在返回 409。
     */
    public TrainAxleProfileResponse createTrainProfile(String trainNo,
                                                       TrainAxleProfileCreateRequest req) {
        String hash = hashTrain(OP_TRAIN_CREATE, trainNo, req.operator(), null,
                req.axleWeightsKg());
        Optional<TrainAxleProfileResponse> replay =
                replayTrainIfPresent(OP_TRAIN_CREATE, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                if (axleRepo.findTrainProfile(trainNo).isPresent()) {
                    // 并发下同键后到的线程在此裁决：同参重放首次结果，异参 409
                    return replayTrainIfPresent(OP_TRAIN_CREATE, req.requestId(), hash)
                            .orElseThrow(() -> conflict("TRAIN_PROFILE_EXISTS",
                                    "列车轴重档案已存在: " + trainNo));
                }
                long now = System.currentTimeMillis();
                long profileId = axleRepo.insertTrainProfile(trainNo, now);
                axleRepo.insertTrainAxles(profileId, req.axleWeightsKg());
                TrainAxleProfileResponse response =
                        new TrainAxleProfileResponse(trainNo, 1, req.axleWeightsKg());
                idemRepo.insert(OP_TRAIN_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveTrainDuplicate(OP_TRAIN_CREATE, req.requestId(), hash, trainNo);
        }
    }

    /**
     * 整体替换列车轴序列，版本加一；expectedVersion 必须匹配。
     * 若使任何已发布计划非法则 409，版本不变，需先取消相关计划。
     */
    public TrainAxleProfileResponse updateTrainProfile(String trainNo,
                                                       TrainAxleProfileUpdateRequest req) {
        String hash = hashTrain(OP_TRAIN_UPDATE, trainNo, req.operator(), req.expectedVersion(),
                req.axleWeightsKg());
        Optional<TrainAxleProfileResponse> replay =
                replayTrainIfPresent(OP_TRAIN_UPDATE, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                TrainAxleProfile current = axleRepo.findTrainProfileForUpdate(trainNo)
                        .orElseThrow(() -> notFound("TRAIN_PROFILE_NOT_FOUND",
                                "列车轴重档案不存在: " + trainNo));
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
                envelopeService.assertTrainProfileUpdateLegal(trainNo, req.axleWeightsKg());
                long now = System.currentTimeMillis();
                long profileId = axleRepo.findTrainProfileId(trainNo)
                        .orElseThrow(() -> new IllegalStateException("列车档案主键缺失: " + trainNo));
                axleRepo.replaceTrainAxles(profileId, req.axleWeightsKg());
                axleRepo.updateTrainProfileVersion(trainNo, current.version() + 1, now);
                TrainAxleProfileResponse response = new TrainAxleProfileResponse(trainNo,
                        current.version() + 1, req.axleWeightsKg());
                idemRepo.insert(OP_TRAIN_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveTrainDuplicate(OP_TRAIN_UPDATE, req.requestId(), hash, trainNo);
        }
    }

    /**
     * 查询列车轴重档案，不存在返回 404。
     */
    public TrainAxleProfileResponse getTrainProfile(String trainNo) {
        TrainAxleProfile profile = axleRepo.findTrainProfile(trainNo)
                .orElseThrow(() -> notFound("TRAIN_PROFILE_NOT_FOUND",
                        "列车轴重档案不存在: " + trainNo));
        return new TrainAxleProfileResponse(profile.trainNo(), profile.version(),
                profile.axleWeightsKg());
    }

    /**
     * 登记区段轴限档案（版本 1）。区段已登记返回 409。
     */
    public SectionAxleLimitResponse createSectionLimit(String sectionId,
                                                       SectionAxleLimitCreateRequest req) {
        String hash = hashSection(OP_SECTION_CREATE, sectionId, req.operator(), null,
                req.singleAxleLimitKg(), req.window3LimitKg());
        Optional<SectionAxleLimitResponse> replay =
                replaySectionIfPresent(OP_SECTION_CREATE, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                if (axleRepo.findSectionLimit(sectionId).isPresent()) {
                    // 并发下同键后到的线程在此裁决：同参重放首次结果，异参 409
                    return replaySectionIfPresent(OP_SECTION_CREATE, req.requestId(), hash)
                            .orElseThrow(() -> conflict("SECTION_LIMIT_EXISTS",
                                    "区段轴限档案已存在: " + sectionId));
                }
                long now = System.currentTimeMillis();
                axleRepo.insertSectionLimit(sectionId, req.singleAxleLimitKg(),
                        req.window3LimitKg(), now);
                SectionAxleLimitResponse response = new SectionAxleLimitResponse(sectionId, 1,
                        req.singleAxleLimitKg(), req.window3LimitKg());
                idemRepo.insert(OP_SECTION_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveSectionDuplicate(OP_SECTION_CREATE, req.requestId(), hash, sectionId);
        }
    }

    /**
     * 修改区段轴限，版本加一；expectedVersion 必须匹配。
     * 若使有任何已发布计划非法则 409，版本不变，需先取消相关计划。
     */
    public SectionAxleLimitResponse updateSectionLimit(String sectionId,
                                                       SectionAxleLimitUpdateRequest req) {
        String hash = hashSection(OP_SECTION_UPDATE, sectionId, req.operator(),
                req.expectedVersion(), req.singleAxleLimitKg(), req.window3LimitKg());
        Optional<SectionAxleLimitResponse> replay =
                replaySectionIfPresent(OP_SECTION_UPDATE, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SectionAxleLimit current = axleRepo.findSectionLimitForUpdate(sectionId)
                        .orElseThrow(() -> notFound("SECTION_LIMIT_NOT_FOUND",
                                "区段轴限档案不存在: " + sectionId));
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
                envelopeService.assertSectionLimitUpdateLegal(sectionId, req.singleAxleLimitKg(),
                        req.window3LimitKg());
                long now = System.currentTimeMillis();
                axleRepo.updateSectionLimit(sectionId, current.version() + 1,
                        req.singleAxleLimitKg(), req.window3LimitKg(), now);
                SectionAxleLimitResponse response = new SectionAxleLimitResponse(sectionId,
                        current.version() + 1, req.singleAxleLimitKg(), req.window3LimitKg());
                idemRepo.insert(OP_SECTION_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveSectionDuplicate(OP_SECTION_UPDATE, req.requestId(), hash, sectionId);
        }
    }

    /**
     * 查询区段轴限档案，不存在返回 404。
     */
    public SectionAxleLimitResponse getSectionLimit(String sectionId) {
        SectionAxleLimit limit = axleRepo.findSectionLimit(sectionId)
                .orElseThrow(() -> notFound("SECTION_LIMIT_NOT_FOUND",
                        "区段轴限档案不存在: " + sectionId));
        return new SectionAxleLimitResponse(limit.sectionId(), limit.version(),
                limit.singleAxleLimitKg(), limit.window3LimitKg());
    }

    // ---------- 内部实现 ----------

    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private Optional<TrainAxleProfileResponse> replayTrainIfPresent(String opType,
                                                                    String requestId,
                                                                    String hash) {
        return replayIfPresent(opType, requestId, hash, TrainAxleProfileResponse.class);
    }

    private Optional<SectionAxleLimitResponse> replaySectionIfPresent(String opType,
                                                                      String requestId,
                                                                      String hash) {
        return replayIfPresent(opType, requestId, hash, SectionAxleLimitResponse.class);
    }

    /**
     * 并发下唯一键冲突后的裁决：同键重放返回首次结果，否则说明档案已存在。
     */
    private TrainAxleProfileResponse resolveTrainDuplicate(String opType, String requestId,
                                                           String hash, String trainNo) {
        return replayTrainIfPresent(opType, requestId, hash)
                .orElseThrow(() -> conflict("TRAIN_PROFILE_EXISTS", "列车轴重档案已存在: " + trainNo));
    }

    private SectionAxleLimitResponse resolveSectionDuplicate(String opType, String requestId,
                                                             String hash, String sectionId) {
        return replaySectionIfPresent(opType, requestId, hash)
                .orElseThrow(() -> conflict("SECTION_LIMIT_EXISTS", "区段轴限档案已存在: " + sectionId));
    }

    private String hashTrain(String opType, String trainNo, String operator,
                             Integer expectedVersion, List<Long> axleWeightsKg) {
        StringBuilder sb = new StringBuilder(opType).append('\n')
                .append(trainNo).append('\n').append(operator).append('\n')
                .append(expectedVersion == null ? "" : expectedVersion);
        for (Long w : axleWeightsKg) {
            sb.append('\n').append(w);
        }
        return sha256(sb.toString());
    }

    private String hashSection(String opType, String sectionId, String operator,
                               Integer expectedVersion, long singleAxleLimitKg,
                               long window3LimitKg) {
        return sha256(opType + '\n' + sectionId + '\n' + operator + '\n'
                + (expectedVersion == null ? "" : expectedVersion) + '\n'
                + singleAxleLimitKg + '\n' + window3LimitKg);
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

    private ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
