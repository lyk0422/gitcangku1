package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.TractionMode;
import com.example.starter.plan.model.TrainProfile;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.TractionRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.TractionSnapshotItem;
import com.example.starter.plan.web.dto.TractionSnapshotResponse;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 列车/区段牵引档案核心业务：档案新增与变更（expectedVersion 乐观校验，成功加一）、
 * 发布/改签前的牵引适配检查、发布时刻冻结快照。
 *
 * <p>串行化约定：档案变更与发布、改签共用全局发布锁（publish_lock 单行），
 * 事务提交顺序即合法串行结果；档案变更若使任一已发布计划非法则整体 409 回滚，
 * 不自动取消或改派。幂等约定与计划写操作一致：同 requestId 同参重放首次响应，
 * 异参 409，失败不占键，业务与去重同事务提交。
 */
@Service
public class TractionService {

    /** 幂等操作类型：列车牵引档案写入。 */
    public static final String OP_TRAIN_PROFILE = "TRAIN_PROFILE";
    /** 幂等操作类型：区段牵引档案写入。 */
    public static final String OP_SECTION_PROFILE = "SECTION_PROFILE";

    private final TractionRepository tractionRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final RequestFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public TractionService(TractionRepository tractionRepo, PlanRepository planRepo,
                           IdempotencyRepository idemRepo, RequestFingerprint fingerprint,
                           ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.tractionRepo = tractionRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.fingerprint = fingerprint;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 新增或变更列车牵引档案。expectedVersion 为 0 表示新增，否则须匹配当前版本。
     * 变更若使任一已发布计划非法则 409，须先取消相关计划。
     */
    public TrainProfileResponse upsertTrainProfile(String trainNo, TrainProfileRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_TRAIN_PROFILE);
        identity.put("operator", req.operator());
        identity.put("trainNo", trainNo);
        identity.put("tractionMode", req.tractionMode().name());
        identity.put("expectedVersion", req.expectedVersion());
        String hash = fingerprint.of(identity);
        Optional<TrainProfileResponse> replay =
                replayIfPresent(OP_TRAIN_PROFILE, req.requestId(), hash, TrainProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 持锁后重查：并发同键请求可能已提交，直接重放首次结果
                Optional<TrainProfileResponse> committed =
                        replayIfPresent(OP_TRAIN_PROFILE, req.requestId(), hash,
                                TrainProfileResponse.class);
                if (committed.isPresent()) {
                    return committed.get();
                }
                long now = System.currentTimeMillis();
                if (req.expectedVersion() == 0) {
                    if (tractionRepo.findTrainProfileForUpdate(trainNo).isPresent()) {
                        throw conflict("VERSION_CONFLICT",
                                "档案已存在，新增须 expectedVersion=0: " + trainNo);
                    }
                    tractionRepo.insertTrainProfile(trainNo, req.tractionMode(), now);
                } else {
                    TrainProfile current = tractionRepo.findTrainProfileForUpdate(trainNo)
                            .orElseThrow(() -> notFound("列车牵引档案不存在: " + trainNo));
                    if (current.version() != req.expectedVersion()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与当前版本 " + current.version() + " 不一致");
                    }
                    List<Map<String, Object>> violations = new ArrayList<>();
                    for (DayPlan plan : planRepo.findPublishedPlansByTrain(trainNo)) {
                        List<Map<String, Object>> issues = findIssues(
                                planRepo.findOccupancies(plan.id()),
                                trainNo, req.tractionMode(), null, null);
                        for (Map<String, Object> issue : issues) {
                            issue.put("scheduleKey", plan.scheduleKey());
                            violations.add(issue);
                        }
                    }
                    if (!violations.isEmpty()) {
                        throw new ApiException(HttpStatus.CONFLICT, "PROFILE_CHANGE_CONFLICT",
                                "变更将使已发布计划非法，须先取消相关计划", violations);
                    }
                    tractionRepo.updateTrainProfile(trainNo, req.tractionMode(),
                            current.version() + 1, now);
                }
                TrainProfileResponse response = loadTrainProfile(trainNo);
                idemRepo.insert(OP_TRAIN_PROFILE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TRAIN_PROFILE, req.requestId(), hash,
                    TrainProfileResponse.class)
                    .orElseThrow(() -> conflict("PROFILE_CONFLICT", "列车牵引档案并发冲突: " + trainNo));
        }
    }
    /**
     * 新增或变更区段牵引档案。expectedVersion 为 0 表示新增，否则须匹配当前版本。
     * 变更若使任一已发布计划非法则 409，须先取消相关计划。
     */
    public SectionProfileResponse upsertSectionProfile(String sectionId,
                                                       SectionProfileRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_SECTION_PROFILE);
        identity.put("operator", req.operator());
        identity.put("sectionId", sectionId);
        identity.put("electrified", req.electrified());
        identity.put("dieselAllowed", req.dieselAllowed());
        identity.put("expectedVersion", req.expectedVersion());
        String hash = fingerprint.of(identity);
        Optional<SectionProfileResponse> replay = replayIfPresent(OP_SECTION_PROFILE,
                req.requestId(), hash, SectionProfileResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 持锁后重查：并发同键请求可能已提交，直接重放首次结果
                Optional<SectionProfileResponse> committed =
                        replayIfPresent(OP_SECTION_PROFILE, req.requestId(), hash,
                                SectionProfileResponse.class);
                if (committed.isPresent()) {
                    return committed.get();
                }
                long now = System.currentTimeMillis();
                if (req.expectedVersion() == 0) {
                    if (tractionRepo.findSectionProfileForUpdate(sectionId).isPresent()) {
                        throw conflict("VERSION_CONFLICT",
                                "档案已存在，新增须 expectedVersion=0: " + sectionId);
                    }
                    tractionRepo.insertSectionProfile(sectionId, req.electrified(),
                            req.dieselAllowed(), now);
                } else {
                    SectionProfile current = tractionRepo.findSectionProfileForUpdate(sectionId)
                            .orElseThrow(() -> notFound("区段牵引档案不存在: " + sectionId));
                    if (current.version() != req.expectedVersion()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与当前版本 " + current.version() + " 不一致");
                    }
                    SectionProfile override = new SectionProfile(current.id(), sectionId,
                            req.electrified(), req.dieselAllowed(), current.version());
                    List<Map<String, Object>> violations = new ArrayList<>();
                    for (DayPlan plan : planRepo.findPublishedPlansBySection(sectionId)) {
                        List<Map<String, Object>> issues = findIssues(
                                planRepo.findOccupancies(plan.id()),
                                null, null, sectionId, override);
                        for (Map<String, Object> issue : issues) {
                            issue.put("scheduleKey", plan.scheduleKey());
                            violations.add(issue);
                        }
                    }
                    if (!violations.isEmpty()) {
                        throw new ApiException(HttpStatus.CONFLICT, "PROFILE_CHANGE_CONFLICT",
                                "变更将使已发布计划非法，须先取消相关计划", violations);
                    }
                    tractionRepo.updateSectionProfile(sectionId, req.electrified(),
                            req.dieselAllowed(), current.version() + 1, now);
                }
                SectionProfileResponse response = loadSectionProfile(sectionId);
                idemRepo.insert(OP_SECTION_PROFILE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_SECTION_PROFILE, req.requestId(), hash,
                    SectionProfileResponse.class)
                    .orElseThrow(() -> conflict("PROFILE_CONFLICT", "区段牵引档案并发冲突: " + sectionId));
        }
    }

    /**
     * 按列车编号查询牵引档案。
     */
    public Optional<TrainProfileResponse> findTrainProfile(String trainNo) {
        return tractionRepo.findTrainProfile(trainNo)
                .map(p -> new TrainProfileResponse(p.trainNo(), p.tractionMode().name(), p.version()));
    }

    /**
     * 按区段 ID 查询牵引档案。
     */
    public Optional<SectionProfileResponse> findSectionProfile(String sectionId) {
        return tractionRepo.findSectionProfile(sectionId)
                .map(p -> new SectionProfileResponse(p.sectionId(), p.electrified(),
                        p.dieselAllowed(), p.version()));
    }

    /**
     * 查询计划发布时刻冻结的牵引快照；计划不存在返回 404。
     */
    public TractionSnapshotResponse getSnapshot(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> notFound("计划不存在: " + scheduleKey));
        List<TractionSnapshotItem> items = tractionRepo.findSnapshots(plan.id()).stream()
                .map(s -> new TractionSnapshotItem(s.trainNo(), s.tractionMode().name(),
                        s.sectionId(), s.electrified(), s.dieselAllowed(), s.sectionVersion()))
                .toList();
        return new TractionSnapshotResponse(scheduleKey, items);
    }

    /**
     * 牵引适配检查：占用涉及的全部列车须有档案，全部区段须有档案且适配
     * （电力车全程供电、柴油车全程允许柴油）；任一不满足抛出 422 并携带区段号。
     */
    public void assertCompatible(List<Occupancy> occupancies) {
        List<Map<String, Object>> issues = findIssues(occupancies, null, null, null, null);
        if (!issues.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRACTION_CHECK_FAILED",
                    "牵引档案缺失或区段不适配", issues);
        }
    }

    /**
     * 发布/改签成功时冻结牵引方式与每段档案值、版本；须在调用方事务内执行。
     */
    public void freezeSnapshot(long planId, List<Occupancy> occupancies, long nowMillis) {
        Set<String> seen = new LinkedHashSet<>();
        for (Occupancy o : occupancies) {
            if (!seen.add(o.trainNo() + "\n" + o.sectionId())) {
                continue;
            }
            TrainProfile train = tractionRepo.findTrainProfile(o.trainNo())
                    .orElseThrow(() -> new IllegalStateException("列车牵引档案缺失: " + o.trainNo()));
            SectionProfile section = tractionRepo.findSectionProfile(o.sectionId())
                    .orElseThrow(() -> new IllegalStateException("区段牵引档案缺失: " + o.sectionId()));
            tractionRepo.insertSnapshot(planId, o.trainNo(), train.tractionMode(), o.sectionId(),
                    section.electrified(), section.dieselAllowed(), section.version(), nowMillis);
        }
    }
    // ---------- 内部实现 ----------

    /**
     * 逐条（按列车+区段去重）评估牵引适配问题；可传入某一列车或区段的拟变更值做预演。
     *
     * @param overrideTrainNo   拟变更列车编号（无预演传 null）
     * @param overrideMode      拟变更牵引方式
     * @param overrideSectionId 拟变更区段 ID（无预演传 null）
     * @param overrideSection   拟变更区段档案值
     */
    private List<Map<String, Object>> findIssues(List<Occupancy> occupancies,
                                                 String overrideTrainNo,
                                                 TractionMode overrideMode,
                                                 String overrideSectionId,
                                                 SectionProfile overrideSection) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Occupancy o : occupancies) {
            if (!seen.add(o.trainNo() + "\n" + o.sectionId())) {
                continue;
            }
            TractionMode mode;
            if (overrideTrainNo != null && overrideTrainNo.equals(o.trainNo())) {
                mode = overrideMode;
            } else {
                Optional<TrainProfile> train = tractionRepo.findTrainProfile(o.trainNo());
                if (train.isEmpty()) {
                    issues.add(issue("TRAIN_PROFILE_MISSING", o, null));
                    continue;
                }
                mode = train.get().tractionMode();
            }
            SectionProfile section;
            if (overrideSectionId != null && overrideSectionId.equals(o.sectionId())) {
                section = overrideSection;
            } else {
                Optional<SectionProfile> found = tractionRepo.findSectionProfile(o.sectionId());
                if (found.isEmpty()) {
                    issues.add(issue("SECTION_PROFILE_MISSING", o, mode));
                    continue;
                }
                section = found.get();
            }
            if (mode == TractionMode.ELECTRIC && !section.electrified()) {
                issues.add(issue("SECTION_NOT_ELECTRIFIED", o, mode));
            }
            if (mode == TractionMode.DIESEL && !section.dieselAllowed()) {
                issues.add(issue("DIESEL_NOT_ALLOWED", o, mode));
            }
        }
        return issues;
    }

    private Map<String, Object> issue(String type, Occupancy o, TractionMode mode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("trainNo", o.trainNo());
        detail.put("sectionId", o.sectionId());
        if (mode != null) {
            detail.put("tractionMode", mode.name());
        }
        return detail;
    }

    private TrainProfileResponse loadTrainProfile(String trainNo) {
        TrainProfile p = tractionRepo.findTrainProfile(trainNo)
                .orElseThrow(() -> notFound("列车牵引档案不存在: " + trainNo));
        return new TrainProfileResponse(p.trainNo(), p.tractionMode().name(), p.version());
    }

    private SectionProfileResponse loadSectionProfile(String sectionId) {
        SectionProfile p = tractionRepo.findSectionProfile(sectionId)
                .orElseThrow(() -> notFound("区段牵引档案不存在: " + sectionId));
        return new SectionProfileResponse(p.sectionId(), p.electrified(), p.dieselAllowed(),
                p.version());
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