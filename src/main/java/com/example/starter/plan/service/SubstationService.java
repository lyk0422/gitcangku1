package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.PowerSlot;
import com.example.starter.plan.model.SectionBinding;
import com.example.starter.plan.model.Substation;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SubstationRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BindSectionRequest;
import com.example.starter.plan.web.dto.CreateSubstationRequest;
import com.example.starter.plan.web.dto.PowerSnapshotView;
import com.example.starter.plan.web.dto.SectionBindingResponse;
import com.example.starter.plan.web.dto.SubstationResponse;
import com.example.starter.plan.web.dto.UpdateSubstationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 变电所与区段绑定核心业务：创建/调上限、绑定/改绑、功率快照查询。
 *
 * <p>幂等约定：写操作携带非空操作者与全局 requestId，按 (操作, 资源, 全参数, 期望版本)
 * 结构化哈希去重；同键同参重放首次完整响应，同键不同参 409；失败不占键，
 * 业务与去重记录同事务原子提交，并发唯一键冲突不外溢。
 *
 * <p>并发约定：调上限与绑定变更和发布/取消共用全局发布锁串行裁决；
 * 调低上限或改绑使现存已发布计划超限则 409。
 */
@Service
public class SubstationService {

    private static final String OP_SUBSTATION_CREATE = "SUBSTATION_CREATE";
    private static final String OP_SUBSTATION_UPDATE = "SUBSTATION_UPDATE";
    private static final String OP_SECTION_BIND = "SECTION_BIND";

    private final SubstationRepository substationRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final PowerBudgetService powerBudget;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public SubstationService(SubstationRepository substationRepo, PlanRepository planRepo,
                             IdempotencyRepository idemRepo, PowerBudgetService powerBudget,
                             ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.substationRepo = substationRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.powerBudget = powerBudget;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建变电所（版本 1，正整数功率上限）。
     */
    public SubstationResponse createSubstation(CreateSubstationRequest req) {
        String hash = hashOf(OP_SUBSTATION_CREATE, req.operator(), req.substationKey(),
                String.valueOf(req.powerLimitKw()));
        Optional<SubstationResponse> replay =
                replayIfPresent(req.requestId(), hash, SubstationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (substationRepo.findByKey(req.substationKey()).isPresent()) {
                    throw conflict("SUBSTATION_KEY_EXISTS", "substationKey 已存在: " + req.substationKey());
                }
                long now = System.currentTimeMillis();
                substationRepo.insertSubstation(req.substationKey(), req.powerLimitKw(), now);
                SubstationResponse response = new SubstationResponse(req.substationKey(),
                        req.powerLimitKw(), 1);
                idemRepo.insert(OP_SUBSTATION_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(req.requestId(), hash, SubstationResponse.class,
                    "SUBSTATION_KEY_EXISTS", "substationKey 已存在: " + req.substationKey());
        }
    }

    /**
     * 调整功率上限：expectedVersion 乐观校验；调低使现存已发布计划超限则 409。
     */
    public SubstationResponse updateLimit(String substationKey, UpdateSubstationRequest req) {
        String hash = hashOf(OP_SUBSTATION_UPDATE, req.operator(), substationKey,
                String.valueOf(req.powerLimitKw()), String.valueOf(req.expectedVersion()));
        Optional<SubstationResponse> replay =
                replayIfPresent(req.requestId(), hash, SubstationResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Substation substation = substationRepo.findByKeyForUpdate(substationKey)
                        .orElseThrow(() -> notFound(substationKey));
                if (req.expectedVersion() != substation.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前版本 " + substation.version() + " 不一致");
                }
                List<String> sections = substationRepo.findSectionsBySubstation(substation.id());
                List<PowerSlot> slots = substationRepo.findPublishedPowerSlotsBySections(sections);
                List<Map<String, Object>> violations =
                        powerBudget.findViolations(substation, req.powerLimitKw(), slots);
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.CONFLICT, "POWER_LIMIT_CONFLICT",
                            "调低上限使现存已发布计划超限", violations);
                }
                long now = System.currentTimeMillis();
                substationRepo.updateLimit(substation.id(), req.powerLimitKw(),
                        substation.version() + 1, now);
                SubstationResponse response = new SubstationResponse(substationKey,
                        req.powerLimitKw(), substation.version() + 1);
                idemRepo.insert(OP_SUBSTATION_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(req.requestId(), hash, SubstationResponse.class,
                    "VERSION_CONFLICT", "版本冲突");
        }
    }

    /**
     * 绑定/改绑区段到变电所。未绑定旧区段须先取消其旧已发布占用；
     * 改绑使目标变电所现存计划超限则 409；重复绑定同一变电所为幂等成功。
     */
    public SectionBindingResponse bindSection(String sectionId, BindSectionRequest req) {
        String hash = hashOf(OP_SECTION_BIND, req.operator(), sectionId, req.substationKey(),
                String.valueOf(req.expectedVersion()));
        Optional<SectionBindingResponse> replay =
                replayIfPresent(req.requestId(), hash, SectionBindingResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Substation target = substationRepo.findByKeyForUpdate(req.substationKey())
                        .orElseThrow(() -> notFound(req.substationKey()));
                if (req.expectedVersion() != target.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与变电所当前版本 " + target.version() + " 不一致");
                }
                long now = System.currentTimeMillis();
                Optional<SectionBinding> existing = substationRepo.findBindingForUpdate(sectionId);
                if (existing.isPresent() && existing.get().substationId() == target.id()) {
                    SectionBindingResponse response =
                            new SectionBindingResponse(sectionId, req.substationKey());
                    idemRepo.insert(OP_SECTION_BIND, req.requestId(), hash, toJson(response), now);
                    return response;
                }
                if (existing.isEmpty()) {
                    List<PowerSlot> legacy =
                            substationRepo.findPublishedPowerSlotsBySections(List.of(sectionId));
                    if (!legacy.isEmpty()) {
                        throw conflict("SECTION_HAS_PUBLISHED_OCCUPANCIES",
                                "区段存在已发布占用，绑定前须先取消: " + sectionId);
                    }
                    substationRepo.insertBinding(sectionId, target.id(), now);
                } else {
                    List<String> sections = new ArrayList<>(
                            substationRepo.findSectionsBySubstation(target.id()));
                    sections.add(sectionId);
                    List<PowerSlot> slots =
                            substationRepo.findPublishedPowerSlotsBySections(sections);
                    List<Map<String, Object>> violations =
                            powerBudget.findViolations(target, target.powerLimitKw(), slots);
                    if (!violations.isEmpty()) {
                        throw new ApiException(HttpStatus.CONFLICT, "POWER_LIMIT_CONFLICT",
                                "改绑使目标变电所现存计划超限", violations);
                    }
                    substationRepo.updateBinding(existing.get().id(), target.id(), now);
                }
                SectionBindingResponse response =
                        new SectionBindingResponse(sectionId, req.substationKey());
                idemRepo.insert(OP_SECTION_BIND, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return resolveDuplicate(req.requestId(), hash, SectionBindingResponse.class,
                    "SECTION_BINDING_CONFLICT", "区段绑定并发冲突: " + sectionId);
        }
    }

    /**
     * 按业务键查询变电所，不存在返回 404。
     */
    public SubstationResponse getSubstation(String substationKey) {
        Substation substation = substationRepo.findByKey(substationKey)
                .orElseThrow(() -> notFound(substationKey));
        return new SubstationResponse(substation.substationKey(), substation.powerLimitKw(),
                substation.version());
    }

    /**
     * 查询计划发布/改签时冻结的功率快照，历史不漂移；计划不存在返回 404。
     */
    public List<PowerSnapshotView> getPowerSnapshots(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        return powerBudget.findSnapshots(plan.id()).stream()
                .map(s -> new PowerSnapshotView(s.sectionId(), s.substationKey(), s.limitVersion(),
                        s.limitKw(), s.powerKw(), s.startUtc(), s.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 全局 requestId 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String requestId, String hash, Class<T> type) {
        return idemRepo.findByKey(requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 并发下唯一键冲突后的裁决：同键重放返回首次结果，否则抛出给定业务冲突。
     */
    private <T> T resolveDuplicate(String requestId, String hash, Class<T> type,
                                   String code, String message) {
        return replayIfPresent(requestId, hash, type)
                .orElseThrow(() -> conflict(code, message));
    }

    /**
     * 结构化参数哈希：操作、资源、全参数与期望版本逐字段长度前缀拼接，字段间无碰撞；
     * 数值按规范化整数值、文本按原样参与。
     */
    private String hashOf(String opType, String... fields) {
        StringBuilder sb = new StringBuilder(opType);
        for (String field : fields) {
            sb.append('\n').append(field.length()).append(':').append(field);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
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

    private ApiException notFound(String substationKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "SUBSTATION_NOT_FOUND",
                "变电所不存在: " + substationKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
