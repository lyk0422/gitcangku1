package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.SectionLimitProfile;
import com.example.starter.plan.model.TrainAxleProfile;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.ProfileRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionLimitProfileCreateRequest;
import com.example.starter.plan.web.dto.SectionLimitProfileResponse;
import com.example.starter.plan.web.dto.SectionLimitProfileUpdateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileResponse;
import com.example.starter.plan.web.dto.TrainAxleProfileUpdateRequest;
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
 * 车轴局部集中承载（轴重包络）核验与档案管理。
 *
 * <p>列车有序轴重档案（2～16 轴，每轴正整数千克）与区段限值档案（单轴上限、任意连续 3 轴
 * 总重上限）均从版本 1 起，更新携带 expectedVersion。发布/改签逐区段检查全部单轴与每个
 * 三轴滑窗（少于 3 轴只查单轴），越限 422 并给出区段与轴序号，整次不写占用；档案缺失 404。
 * 档案更新若使任何已发布计划在完整后态下非法则 409 且版本不变，需先取消相关计划。
 * 所有总和以 Math.addExact 精确计算，溢出即失败而非放行。
 *
 * <p>写操作按 (操作类型, requestId) 幂等：同键同参重放返回首次完整响应，同键异参 409，
 * 失败不占键；业务与去重同事务，唯一键竞争不泄漏异常。
 */
@Service
public class AxleEnvelopeService {

    private static final String OP_TRAIN_CREATE = "TRAIN_AXLE_CREATE";
    private static final String OP_TRAIN_UPDATE = "TRAIN_AXLE_UPDATE";
    private static final String OP_SECTION_CREATE = "SECTION_LIMIT_CREATE";
    private static final String OP_SECTION_UPDATE = "SECTION_LIMIT_UPDATE";

    private final ProfileRepository profileRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AxleEnvelopeService(ProfileRepository profileRepo, PlanRepository planRepo,
                               IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                               PlatformTransactionManager txManager) {
        this.profileRepo = profileRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建列车有序轴重档案（版本 1）。
     */
    public TrainAxleProfileResponse createTrainProfile(TrainAxleProfileCreateRequest req) {
        String hash = hashTrainCreate(req);
        Optional<TrainAxleProfileResponse> replay =
                replayIfPresent(OP_TRAIN_CREATE, req.requestId(), hash, TrainAxleProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        String weightsJson = toJson(req.axleWeightsKg());
        try {
            return tx.execute(status -> {
                if (profileRepo.findTrainProfile(req.trainNo()).isPresent()) {
                    throw conflict("TRAIN_PROFILE_EXISTS", "列车轴重档案已存在: " + req.trainNo());
                }
                long now = System.currentTimeMillis();
                profileRepo.insertTrainProfile(req.trainNo(), weightsJson, now);
                TrainAxleProfileResponse response =
                        new TrainAxleProfileResponse(req.trainNo(), 1, req.axleWeightsKg());
                idemRepo.insert(OP_TRAIN_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRAIN_CREATE, req.requestId(), hash, TrainAxleProfileResponse.class)
                    .orElseThrow(() -> conflict("TRAIN_PROFILE_EXISTS",
                            "列车轴重档案已存在: " + req.trainNo()));
        }
    }

    /**
     * 更新列车有序轴重档案：expectedVersion 必须匹配；若使任何已发布计划非法则 409 且版本不变。
     */
    public TrainAxleProfileResponse updateTrainProfile(String trainNo,
                                                       TrainAxleProfileUpdateRequest req) {
        String hash = hashTrainUpdate(trainNo, req);
        Optional<TrainAxleProfileResponse> replay =
                replayIfPresent(OP_TRAIN_UPDATE, req.requestId(), hash, TrainAxleProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        String weightsJson = toJson(req.axleWeightsKg());
        try {
            return tx.execute(status -> {
                TrainAxleProfile current = profileRepo.findTrainProfileForUpdate(trainNo)
                        .orElseThrow(() -> notFoundTrain(trainNo));
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
                // 完整后态核验：引用该列车的所有已发布计划在新轴序列下仍须合法
                List<Map<String, Object>> conflicts = new ArrayList<>();
                for (PlanRepository.PublishedAxleRef ref
                        : planRepo.findPublishedAxleRefsByTrain(trainNo)) {
                    SectionLimitProfile limits = profileRepo.findSectionProfile(ref.sectionId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "已发布计划区段限值档案缺失: " + ref.sectionId()));
                    for (Map<String, Object> detail : checkAxleEnvelope(ref.sectionId(),
                            trainNo, req.axleWeightsKg(), limits)) {
                        detail.put("scheduleKey", ref.scheduleKey());
                        conflicts.add(detail);
                    }
                }
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "PROFILE_UPDATE_CONFLICT",
                            "更新将使已发布计划非法，需先取消相关计划，不自动减载", conflicts);
                }
                long now = System.currentTimeMillis();
                profileRepo.updateTrainProfile(trainNo, current.version() + 1, weightsJson, now);
                TrainAxleProfileResponse response = new TrainAxleProfileResponse(trainNo,
                        current.version() + 1, req.axleWeightsKg());
                idemRepo.insert(OP_TRAIN_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRAIN_UPDATE, req.requestId(), hash, TrainAxleProfileResponse.class)
                    .orElseThrow(() -> conflict("TRAIN_PROFILE_EXISTS",
                            "列车轴重档案并发冲突: " + trainNo));
        }
    }

    /**
     * 按列车编号查询轴重档案，不存在返回 404。
     */
    public TrainAxleProfileResponse getTrainProfile(String trainNo) {
        TrainAxleProfile profile = profileRepo.findTrainProfile(trainNo)
                .orElseThrow(() -> notFoundTrain(trainNo));
        return new TrainAxleProfileResponse(profile.trainNo(), profile.version(),
                profile.axleWeightsKg());
    }

    /**
     * 创建区段轴重限值档案（版本 1）。
     */
    public SectionLimitProfileResponse createSectionProfile(SectionLimitProfileCreateRequest req) {
        String hash = hashSectionCreate(req);
        Optional<SectionLimitProfileResponse> replay = replayIfPresent(
                OP_SECTION_CREATE, req.requestId(), hash, SectionLimitProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (profileRepo.findSectionProfile(req.sectionId()).isPresent()) {
                    throw conflict("SECTION_PROFILE_EXISTS", "区段限值档案已存在: " + req.sectionId());
                }
                long now = System.currentTimeMillis();
                profileRepo.insertSectionProfile(req.sectionId(), req.singleAxleLimitKg(),
                        req.window3LimitKg(), now);
                SectionLimitProfileResponse response = new SectionLimitProfileResponse(
                        req.sectionId(), 1, req.singleAxleLimitKg(), req.window3LimitKg());
                idemRepo.insert(OP_SECTION_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_CREATE, req.requestId(), hash,
                    SectionLimitProfileResponse.class)
                    .orElseThrow(() -> conflict("SECTION_PROFILE_EXISTS",
                            "区段限值档案已存在: " + req.sectionId()));
        }
    }

    /**
     * 更新区段轴重限值档案：expectedVersion 必须匹配；若使任何已发布计划非法则 409 且版本不变。
     */
    public SectionLimitProfileResponse updateSectionProfile(String sectionId,
                                                            SectionLimitProfileUpdateRequest req) {
        String hash = hashSectionUpdate(sectionId, req);
        Optional<SectionLimitProfileResponse> replay = replayIfPresent(
                OP_SECTION_UPDATE, req.requestId(), hash, SectionLimitProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                SectionLimitProfile current = profileRepo.findSectionProfileForUpdate(sectionId)
                        .orElseThrow(() -> notFoundSection(sectionId));
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + current.version() + " 不一致");
                }
                // 完整后态核验：引用该区段的所有已发布计划在新限值下仍须合法
                List<Map<String, Object>> conflicts = new ArrayList<>();
                for (PlanRepository.PublishedAxleRef ref
                        : planRepo.findPublishedAxleRefsBySection(sectionId)) {
                    TrainAxleProfile train = profileRepo.findTrainProfile(ref.trainNo())
                            .orElseThrow(() -> new IllegalStateException(
                                    "已发布计划列车轴重档案缺失: " + ref.trainNo()));
                    for (Map<String, Object> detail : checkAxleEnvelope(sectionId, ref.trainNo(),
                            train.axleWeightsKg(), req.singleAxleLimitKg(), req.window3LimitKg())) {
                        detail.put("scheduleKey", ref.scheduleKey());
                        conflicts.add(detail);
                    }
                }
                if (!conflicts.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "PROFILE_UPDATE_CONFLICT",
                            "更新将使已发布计划非法，需先取消相关计划，不自动减载", conflicts);
                }
                long now = System.currentTimeMillis();
                profileRepo.updateSectionProfile(sectionId, current.version() + 1,
                        req.singleAxleLimitKg(), req.window3LimitKg(), now);
                SectionLimitProfileResponse response = new SectionLimitProfileResponse(sectionId,
                        current.version() + 1, req.singleAxleLimitKg(), req.window3LimitKg());
                idemRepo.insert(OP_SECTION_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_UPDATE, req.requestId(), hash,
                    SectionLimitProfileResponse.class)
                    .orElseThrow(() -> conflict("SECTION_PROFILE_EXISTS",
                            "区段限值档案并发冲突: " + sectionId));
        }
    }

    /**
     * 按区段 ID 查询限值档案，不存在返回 404。
     */
    public SectionLimitProfileResponse getSectionProfile(String sectionId) {
        SectionLimitProfile profile = profileRepo.findSectionProfile(sectionId)
                .orElseThrow(() -> notFoundSection(sectionId));
        return new SectionLimitProfileResponse(profile.sectionId(), profile.version(),
                profile.singleAxleLimitKg(), profile.window3LimitKg());
    }

    // ---------- 发布/改签轴重包络核验与冻结（须在调用方事务内） ----------

    /**
     * 对候选计划的全部占用逐区段核验轴重包络（所有单轴与每个三轴滑窗），
     * 全部通过后同事务冻结当时轴序列、各区段限值与档案版本。
     * 档案缺失抛 404；任一越限抛 422（携带区段与轴序号），整次不写占用与快照。
     */
    public void checkAndFreeze(DayPlan plan, List<Occupancy> occupancies) {
        record Snapshot(Occupancy occ, TrainAxleProfile train, SectionLimitProfile limits) {
        }
        List<Snapshot> pending = new ArrayList<>();
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Occupancy occ : occupancies) {
            TrainAxleProfile train = profileRepo.findTrainProfile(occ.trainNo())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "TRAIN_PROFILE_NOT_FOUND", "列车轴重档案缺失: " + occ.trainNo()));
            SectionLimitProfile limits = profileRepo.findSectionProfile(occ.sectionId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "SECTION_PROFILE_NOT_FOUND", "区段限值档案缺失: " + occ.sectionId()));
            violations.addAll(checkAxleEnvelope(occ.sectionId(), occ.trainNo(),
                    train.axleWeightsKg(), limits));
            pending.add(new Snapshot(occ, train, limits));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AXLE_LIMIT_EXCEEDED",
                    "轴重包络越限，整次不写占用", violations);
        }
        long now = System.currentTimeMillis();
        for (Snapshot s : pending) {
            profileRepo.insertAxleSnapshot(plan.id(), s.occ().seq(), s.occ().trainNo(),
                    s.occ().sectionId(), toJson(s.train().axleWeightsKg()), s.train().version(),
                    s.limits().version(), s.limits().singleAxleLimitKg(),
                    s.limits().window3LimitKg(), now);
        }
    }

    /**
     * 轴重包络检查：全部单轴不超过单轴上限；每个连续 3 轴滑窗总重不超过窗口上限
     * （少于 3 轴只查单轴）。总和以 Math.addExact 精确计算，溢出即失败而非放行。
     */
    private List<Map<String, Object>> checkAxleEnvelope(String sectionId, String trainNo,
                                                        List<Integer> weights,
                                                        SectionLimitProfile limits) {
        return checkAxleEnvelope(sectionId, trainNo, weights, limits.singleAxleLimitKg(),
                limits.window3LimitKg());
    }

    /**
     * 轴重包络检查（显式限值），供档案更新的完整后态核验复用。
     */
    private List<Map<String, Object>> checkAxleEnvelope(String sectionId, String trainNo,
                                                        List<Integer> weights,
                                                        long singleAxleLimitKg,
                                                        long window3LimitKg) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            long weight = weights.get(i);
            if (weight > singleAxleLimitKg) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "SINGLE_AXLE_EXCEEDED");
                detail.put("sectionId", sectionId);
                detail.put("trainNo", trainNo);
                detail.put("axleIndex", i);
                detail.put("weightKg", weight);
                detail.put("limitKg", singleAxleLimitKg);
                violations.add(detail);
            }
        }
        for (int i = 0; i + 2 < weights.size(); i++) {
            long sum = Math.addExact(Math.addExact((long) weights.get(i), weights.get(i + 1)),
                    weights.get(i + 2));
            if (sum > window3LimitKg) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "WINDOW3_EXCEEDED");
                detail.put("sectionId", sectionId);
                detail.put("trainNo", trainNo);
                detail.put("windowStart", i);
                detail.put("sumKg", sum);
                detail.put("limitKg", window3LimitKg);
                violations.add(detail);
            }
        }
        return violations;
    }

    // ---------- 幂等与散列 ----------

    /**
     * 幂等重放（泛型）：存在记录且参数一致返回首次结果；参数不一致抛 409。
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

    private String hashTrainCreate(TrainAxleProfileCreateRequest req) {
        return sha256(OP_TRAIN_CREATE + '\n' + req.trainNo() + '\n' + req.operator()
                + '\n' + req.axleWeightsKg());
    }

    private String hashTrainUpdate(String trainNo, TrainAxleProfileUpdateRequest req) {
        return sha256(OP_TRAIN_UPDATE + '\n' + trainNo + '\n' + req.expectedVersion()
                + '\n' + req.operator() + '\n' + req.axleWeightsKg());
    }

    private String hashSectionCreate(SectionLimitProfileCreateRequest req) {
        return sha256(OP_SECTION_CREATE + '\n' + req.sectionId() + '\n' + req.operator()
                + '\n' + req.singleAxleLimitKg() + '\n' + req.window3LimitKg());
    }

    private String hashSectionUpdate(String sectionId, SectionLimitProfileUpdateRequest req) {
        return sha256(OP_SECTION_UPDATE + '\n' + sectionId + '\n' + req.expectedVersion()
                + '\n' + req.operator() + '\n' + req.singleAxleLimitKg()
                + '\n' + req.window3LimitKg());
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

    private ApiException notFoundTrain(String trainNo) {
        return new ApiException(HttpStatus.NOT_FOUND, "TRAIN_PROFILE_NOT_FOUND",
                "列车轴重档案不存在: " + trainNo);
    }

    private ApiException notFoundSection(String sectionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "SECTION_PROFILE_NOT_FOUND",
                "区段限值档案不存在: " + sectionId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
