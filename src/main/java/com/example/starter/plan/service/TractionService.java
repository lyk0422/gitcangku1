package com.example.starter.plan.service;

import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TractionMode;
import com.example.starter.plan.model.TrainTraction;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.TractionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreateSectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.TrainTractionResponse;
import com.example.starter.plan.web.dto.UpdateSectionProfileRequest;
import com.example.starter.plan.web.dto.UpsertTrainTractionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 列车牵引档案与区段牵引档案核心业务：档案查询、创建与变更。
 *
 * <p>档案变更（列车牵引方式 upsert、区段档案变更）与发布/改签共用全局发布锁串行化，
 * 形成合法串行结果：若变更使任一已发布计划非法则 409，须先取消相关计划，不自动取消或改派。
 * 写操作按 (操作类型, requestId) 幂等，身份含操作、操作者、资源及全部参数和期望版本，
 * 以规范化 JSON 结构化比较避免分隔符碰撞；仅成功结果占用幂等键，失败可修正后重试。
 */
@Service
public class TractionService {

    private static final String OP_TRACTION_UPSERT = "TRACTION_UPSERT";
    private static final String OP_SECTION_CREATE = "SECTION_CREATE";
    private static final String OP_SECTION_UPDATE = "SECTION_UPDATE";

    private final TractionRepository tractionRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public TractionService(TractionRepository tractionRepo, PlanRepository planRepo,
                           IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                           PlatformTransactionManager txManager) {
        this.tractionRepo = tractionRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 新增或变更列车牵引档案。若新方式使任一已发布计划非法则 409（须先取消相关计划）。
     */
    public TrainTractionResponse upsertTrainTraction(String trainNo, UpsertTrainTractionRequest req) {
        String hash = hashIdentity(OP_TRACTION_UPSERT, req.operator(), trainNo,
                req.tractionMode().name());
        Optional<TrainTractionResponse> replay =
                replayIfPresent(OP_TRACTION_UPSERT, req.requestId(), hash, TrainTractionResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                List<Map<String, Object>> violations =
                        findTrainModeViolations(trainNo, req.tractionMode());
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "PROFILE_IN_USE",
                            "牵引方式变更使已发布计划非法，须先取消相关计划", violations);
                }
                long now = System.currentTimeMillis();
                if (tractionRepo.updateTrainTraction(trainNo, req.tractionMode(), now) == 0) {
                    tractionRepo.insertTrainTraction(trainNo, req.tractionMode(), now);
                }
                TrainTractionResponse response =
                        new TrainTractionResponse(trainNo, req.tractionMode().name());
                idemRepo.insert(OP_TRACTION_UPSERT, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRACTION_UPSERT, req.requestId(), hash, TrainTractionResponse.class)
                    .orElseThrow(() -> conflict("TRACTION_CONFLICT", "列车牵引档案并发冲突: " + trainNo));
        }
    }

    /**
     * 创建区段牵引档案（初始版本 1）；区段已存在返回 409。
     */
    public SectionProfileResponse createSectionProfile(CreateSectionProfileRequest req) {
        String hash = hashIdentity(OP_SECTION_CREATE, req.operator(), req.sectionId(),
                req.electrified(), req.dieselAllowed());
        Optional<SectionProfileResponse> replay =
                replayIfPresent(OP_SECTION_CREATE, req.requestId(), hash, SectionProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (tractionRepo.findSectionProfile(req.sectionId()).isPresent()) {
                    throw conflict("SECTION_PROFILE_EXISTS", "区段档案已存在: " + req.sectionId());
                }
                long now = System.currentTimeMillis();
                tractionRepo.insertSectionProfile(req.sectionId(), req.electrified(),
                        req.dieselAllowed(), now);
                SectionProfileResponse response = new SectionProfileResponse(req.sectionId(),
                        req.electrified(), req.dieselAllowed(), 1);
                idemRepo.insert(OP_SECTION_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_CREATE, req.requestId(), hash, SectionProfileResponse.class)
                    .orElseThrow(() -> conflict("SECTION_PROFILE_EXISTS",
                            "区段档案已存在: " + req.sectionId()));
        }
    }

    /**
     * 变更区段牵引档案：expectedVersion 乐观校验，成功版本加一。
     * 若变更使任一已发布计划非法则 409，须先取消相关计划，不自动取消或改派。
     */
    public SectionProfileResponse updateSectionProfile(String sectionId,
                                                       UpdateSectionProfileRequest req) {
        String hash = hashIdentity(OP_SECTION_UPDATE, req.operator(), sectionId,
                req.electrified(), req.dieselAllowed(), req.expectedVersion());
        Optional<SectionProfileResponse> replay =
                replayIfPresent(OP_SECTION_UPDATE, req.requestId(), hash, SectionProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                SectionProfile profile = tractionRepo.findSectionProfileForUpdate(sectionId)
                        .orElseThrow(() -> notFound("区段档案不存在: " + sectionId));
                if (req.expectedVersion() != profile.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + profile.version() + " 不一致");
                }
                List<Map<String, Object>> violations = findSectionChangeViolations(sectionId,
                        req.electrified(), req.dieselAllowed());
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "PROFILE_IN_USE",
                            "区段档案变更使已发布计划非法，须先取消相关计划", violations);
                }
                long now = System.currentTimeMillis();
                tractionRepo.updateSectionProfile(sectionId, req.electrified(),
                        req.dieselAllowed(), profile.version() + 1, now);
                SectionProfileResponse response = new SectionProfileResponse(sectionId,
                        req.electrified(), req.dieselAllowed(), profile.version() + 1);
                idemRepo.insert(OP_SECTION_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_UPDATE, req.requestId(), hash, SectionProfileResponse.class)
                    .orElseThrow(() -> conflict("SECTION_PROFILE_CONFLICT",
                            "区段档案并发冲突: " + sectionId));
        }
    }

    /**
     * 查询列车牵引档案，不存在返回 404。
     */
    public TrainTractionResponse getTrainTraction(String trainNo) {
        TrainTraction traction = tractionRepo.findTrainTraction(trainNo)
                .orElseThrow(() -> notFound("列车牵引档案不存在: " + trainNo));
        return new TrainTractionResponse(traction.trainNo(), traction.tractionMode().name());
    }

    /**
     * 查询区段牵引档案，不存在返回 404。
     */
    public SectionProfileResponse getSectionProfile(String sectionId) {
        SectionProfile profile = tractionRepo.findSectionProfile(sectionId)
                .orElseThrow(() -> notFound("区段档案不存在: " + sectionId));
        return new SectionProfileResponse(profile.sectionId(), profile.electrified(),
                profile.dieselAllowed(), profile.version());
    }

    // ---------- 内部实现 ----------

    /**
     * 检查列车按新牵引方式是否仍适配其已发布计划占用的全部区段。
     */
    private List<Map<String, Object>> findTrainModeViolations(String trainNo, TractionMode newMode) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (TractionRepository.PublishedUsage usage : tractionRepo.findPublishedUsageByTrain(trainNo)) {
            Optional<SectionProfile> profile = tractionRepo.findSectionProfile(usage.sectionId());
            boolean compatible = profile.isPresent() && isCompatible(newMode, profile.get());
            if (!compatible) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "TRAIN_TRACTION_INCOMPATIBLE");
                detail.put("scheduleKey", usage.scheduleKey());
                detail.put("trainNo", trainNo);
                detail.put("tractionMode", newMode.name());
                detail.put("sectionId", usage.sectionId());
                violations.add(detail);
            }
        }
        return violations;
    }

    /**
     * 检查区段档案按新值变更后，该区段上已发布计划的占用是否仍合法。
     */
    private List<Map<String, Object>> findSectionChangeViolations(String sectionId,
                                                                  boolean electrified,
                                                                  boolean dieselAllowed) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (TractionRepository.PublishedUsage usage
                : tractionRepo.findPublishedUsageBySection(sectionId)) {
            Optional<TrainTraction> traction = tractionRepo.findTrainTraction(usage.trainNo());
            boolean compatible = traction.isPresent()
                    && isCompatible(traction.get().tractionMode(), electrified, dieselAllowed);
            if (!compatible) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "SECTION_PROFILE_INCOMPATIBLE");
                detail.put("scheduleKey", usage.scheduleKey());
                detail.put("trainNo", usage.trainNo());
                detail.put("sectionId", sectionId);
                violations.add(detail);
            }
        }
        return violations;
    }

    private static boolean isCompatible(TractionMode mode, SectionProfile profile) {
        return isCompatible(mode, profile.electrified(), profile.dieselAllowed());
    }

    private static boolean isCompatible(TractionMode mode, boolean electrified,
                                        boolean dieselAllowed) {
        return mode == TractionMode.ELECTRIC ? electrified : dieselAllowed;
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
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
     * 幂等身份：操作、操作者、资源及全部参数和期望版本序列化为规范化 JSON 后取 SHA-256，
     * 结构化比较避免分隔符碰撞。
     */
    private String hashIdentity(Object... parts) {
        try {
            return sha256(objectMapper.writeValueAsString(List.of(parts)));
        } catch (Exception e) {
            throw new IllegalStateException("幂等身份序列化失败", e);
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

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}