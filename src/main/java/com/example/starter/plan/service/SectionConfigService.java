package com.example.starter.plan.service;

import com.example.starter.plan.model.SectionConfig;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SectionConfigRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionConfigCreateRequest;
import com.example.starter.plan.web.dto.SectionConfigResponse;
import com.example.starter.plan.web.dto.SectionConfigUpdateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 单线区段换向配置业务：创建（版本 1）、按 expectedVersion 更新与查询。
 *
 * <p>配置创建/更新与发布、改签、取消经同一全局发布锁串行裁决；创建与更新前均验证
 * 当前已发布集合在新配置下合法（无方向占用须先取消，不能猜方向；增大间隔导致
 * 已发布集合非法则 409）。幂等身份为操作、操作者、资源与全部参数（含期望版本）
 * 的结构化 JSON 摘要，避免分隔符碰撞；仅成功结果占键，失败可修正后重试。
 */
@Service
public class SectionConfigService {

    private static final String OP_CONFIG_CREATE = "CONFIG_CREATE";
    private static final String OP_CONFIG_UPDATE = "CONFIG_UPDATE";

    private final SectionConfigRepository configRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final SectionClearanceValidator clearanceValidator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public SectionConfigService(SectionConfigRepository configRepo, PlanRepository planRepo,
                                IdempotencyRepository idemRepo,
                                SectionClearanceValidator clearanceValidator,
                                ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.configRepo = configRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.clearanceValidator = clearanceValidator;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建区段配置（版本 1）。区段已配置、或该区段存在无法纳入新配置的已发布占用时 409。
     */
    public SectionConfigResponse create(SectionConfigCreateRequest req) {
        if (req.directionForward().equals(req.directionBackward())) {
            throw badRequest("正向与反向标识必须不同: " + req.directionForward());
        }
        String hash = hashConfig(OP_CONFIG_CREATE, req.operator(), req.sectionId(),
                req.directionForward(), req.directionBackward(), req.clearanceSeconds(), null);
        Optional<SectionConfigResponse> replay = replayIfPresent(OP_CONFIG_CREATE, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内重放：并发下同 requestId 的首个事务提交后，后续事务在此命中首次结果
                Optional<SectionConfigResponse> inTx =
                        replayIfPresent(OP_CONFIG_CREATE, req.requestId(), hash);
                if (inTx.isPresent()) {
                    return inTx.get();
                }
                if (configRepo.findBySectionId(req.sectionId()).isPresent()) {
                    throw conflict("SECTION_CONFIG_EXISTS", "区段已存在单线配置: " + req.sectionId());
                }
                SectionConfig pending = new SectionConfig(0L, req.sectionId(),
                        req.directionForward(), req.directionBackward(), req.clearanceSeconds(), 1);
                clearanceValidator.validatePublishedSet(req.sectionId(), pending);
                long now = System.currentTimeMillis();
                configRepo.insert(req.sectionId(), req.directionForward(), req.directionBackward(),
                        req.clearanceSeconds(), now);
                SectionConfigResponse response = toResponse(
                        configRepo.findBySectionId(req.sectionId()).orElseThrow());
                idemRepo.insert(OP_CONFIG_CREATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONFIG_CREATE, req.requestId(), hash)
                    .orElseThrow(() -> conflict("SECTION_CONFIG_EXISTS",
                            "区段已存在单线配置: " + req.sectionId()));
        }
    }

    /**
     * 更新区段配置，expectedVersion 必须匹配，成功版本加一；
     * 新配置下当前已发布集合非法（含增大间隔导致的清空不足）则 409。
     */
    public SectionConfigResponse update(String sectionId, SectionConfigUpdateRequest req) {
        if (req.directionForward().equals(req.directionBackward())) {
            throw badRequest("正向与反向标识必须不同: " + req.directionForward());
        }
        String hash = hashConfig(OP_CONFIG_UPDATE, req.operator(), sectionId,
                req.directionForward(), req.directionBackward(), req.clearanceSeconds(),
                req.expectedVersion());
        Optional<SectionConfigResponse> replay = replayIfPresent(OP_CONFIG_UPDATE, req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内重放：并发下同 requestId 的首个事务提交后，后续事务在此命中首次结果
                Optional<SectionConfigResponse> inTx =
                        replayIfPresent(OP_CONFIG_UPDATE, req.requestId(), hash);
                if (inTx.isPresent()) {
                    return inTx.get();
                }
                SectionConfig current = configRepo.findBySectionIdForUpdate(sectionId)
                        .orElseThrow(() -> notFound(sectionId));
                if (req.expectedVersion() != current.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion() + " 与当前配置版本 "
                                    + current.version() + " 不一致");
                }
                SectionConfig pending = new SectionConfig(current.id(), sectionId,
                        req.directionForward(), req.directionBackward(), req.clearanceSeconds(),
                        current.version() + 1);
                clearanceValidator.validatePublishedSet(sectionId, pending);
                long now = System.currentTimeMillis();
                configRepo.update(sectionId, req.directionForward(), req.directionBackward(),
                        req.clearanceSeconds(), current.version() + 1, now);
                SectionConfigResponse response = toResponse(
                        configRepo.findBySectionId(sectionId).orElseThrow());
                idemRepo.insert(OP_CONFIG_UPDATE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_CONFIG_UPDATE, req.requestId(), hash)
                    .orElseThrow(() -> conflict("SECTION_CONFIG_EXISTS",
                            "区段配置并发冲突: " + sectionId));
        }
    }

    /**
     * 按区段查询配置，不存在返回 404。
     */
    public SectionConfigResponse get(String sectionId) {
        return configRepo.findBySectionId(sectionId)
                .map(this::toResponse)
                .orElseThrow(() -> notFound(sectionId));
    }

    // ---------- 内部实现 ----------

    private SectionConfigResponse toResponse(SectionConfig config) {
        return new SectionConfigResponse(config.sectionId(), config.directionForward(),
                config.directionBackward(), config.clearanceSeconds(), config.version());
    }

    /**
     * 幂等重放：存在记录且身份一致返回首次结果；身份不一致抛 409。
     */
    private Optional<SectionConfigResponse> replayIfPresent(String opType, String requestId,
                                                            String hash) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson());
        });
    }

    /**
     * 结构化幂等身份：操作、操作者、资源与全部参数（含期望版本）放入有序结构，
     * 序列化为 JSON 后取 SHA-256，避免分隔符拼接碰撞。
     */
    private String hashConfig(String opType, String operator, String sectionId,
                              String directionForward, String directionBackward,
                              Integer clearanceSeconds, Integer expectedVersion) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", opType);
        identity.put("operator", operator);
        identity.put("sectionId", sectionId);
        identity.put("directionForward", directionForward);
        identity.put("directionBackward", directionBackward);
        identity.put("clearanceSeconds", clearanceSeconds);
        identity.put("expectedVersion", expectedVersion);
        return sha256(toJson(identity));
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

    private SectionConfigResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, SectionConfigResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFound(String sectionId) {
        return new ApiException(HttpStatus.NOT_FOUND, "SECTION_CONFIG_NOT_FOUND",
                "区段单线配置不存在: " + sectionId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
