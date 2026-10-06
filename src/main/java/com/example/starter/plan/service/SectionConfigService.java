package com.example.starter.plan.service;

import com.example.starter.plan.model.DatedSlot;
import com.example.starter.plan.model.SectionConfig;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.SectionConfigRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionConfigRequest;
import com.example.starter.plan.web.dto.SectionConfigResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
 * 单线区段换向配置业务：创建/更新与查询。
 *
 * <p>配置与发布、改签、取消共用同一全局串行边界（publish_lock）；
 * 创建/更新前校验当前已发布集合：存在旧无方向占用返回 409（须先取消，不猜方向），
 * 增大间隔导致已发布集合相邻反向对不满足新间隔返回 409（携带实际秒数与要求值）。
 * 幂等身份含操作、操作者、资源与全部参数（含期望版本），结构化 JSON 取 SHA-256。
 */
@Service
public class SectionConfigService {

    private static final String OP_CONFIG_UPSERT = "CONFIG_UPSERT";

    private final SectionConfigRepository configRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public SectionConfigService(SectionConfigRepository configRepo, PlanRepository planRepo,
                                IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                                PlatformTransactionManager txManager) {
        this.configRepo = configRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建或更新单线区段换向配置。配置不存在时创建（版本 1，expectedVersion 必须为 null）；
     * 已存在时更新（expectedVersion 必须等于当前版本，成功版本加一）。
     */
    public SectionConfigResponse upsert(String sectionId, SectionConfigRequest req) {
        String hash = hashUpsert(sectionId, req);
        Optional<SectionConfigResponse> replay = replayIfPresent(req.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 串行边界内复查：并发同键请求在首次提交后进入时直接重放，不误判为更新
                Optional<SectionConfigResponse> replayed = replayIfPresent(req.requestId(), hash);
                if (replayed.isPresent()) {
                    return replayed.get();
                }
                Optional<SectionConfig> existing = configRepo.findForUpdate(sectionId);
                long now = System.currentTimeMillis();
                SectionConfig config;
                if (existing.isEmpty()) {
                    if (req.expectedVersion() != null) {
                        throw badRequest("配置不存在，创建时 expectedVersion 必须为 null");
                    }
                    validatePublishedSetLegal(sectionId, req.clearanceSeconds());
                    configRepo.insert(sectionId, req.clearanceSeconds(), now);
                    config = new SectionConfig(sectionId, req.clearanceSeconds(), 1);
                } else {
                    SectionConfig current = existing.get();
                    if (req.expectedVersion() == null) {
                        throw badRequest("配置已存在，更新须携带 expectedVersion");
                    }
                    if (req.expectedVersion() != current.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与当前配置版本 " + current.version() + " 不一致");
                    }
                    validatePublishedSetLegal(sectionId, req.clearanceSeconds());
                    configRepo.update(sectionId, req.clearanceSeconds(), current.version() + 1, now);
                    config = new SectionConfig(sectionId, req.clearanceSeconds(),
                            current.version() + 1);
                }
                SectionConfigResponse response = new SectionConfigResponse(config.sectionId(),
                        config.clearanceSeconds(), config.version());
                idemRepo.insert(OP_CONFIG_UPSERT, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(req.requestId(), hash)
                    .orElseThrow(() -> conflict("SECTION_CONFIG_CONFLICT",
                            "区段配置并发冲突: " + sectionId));
        }
    }

    /**
     * 查询区段配置，不存在返回 404。
     */
    public SectionConfigResponse get(String sectionId) {
        SectionConfig config = configRepo.find(sectionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SECTION_CONFIG_NOT_FOUND",
                        "区段配置不存在: " + sectionId));
        return new SectionConfigResponse(config.sectionId(), config.clearanceSeconds(),
                config.version());
    }

    /**
     * 校验当前已发布集合在新清空间隔下合法：存在旧无方向占用报 409（须先取消）；
     * 逐运营日合并排序后相邻反向对间隔不足报 409（携带实际秒数与要求值）。
     */
    private void validatePublishedSetLegal(String sectionId, int clearanceSeconds) {
        List<DatedSlot> slots = planRepo.findPublishedSlotsBySection(sectionId);
        for (DatedSlot slot : slots) {
            if (slot.direction() == null) {
                throw conflict("DIRECTIONLESS_PUBLISHED",
                        "区段 " + sectionId + " 存在未指定方向的已发布占用（计划 "
                                + slot.scheduleKey() + "），须先取消该计划再配置，不能猜测方向");
            }
        }
        // 按运营日分组（查询已按 op_date, start_utc 排序），逐相邻对校验
        LocalDate currentDay = null;
        DatedSlot predecessor = null;
        List<Map<String, Object>> violations = new ArrayList<>();
        for (DatedSlot slot : slots) {
            if (!slot.opDate().equals(currentDay)) {
                currentDay = slot.opDate();
                predecessor = null;
            }
            if (predecessor != null && slot.direction() != predecessor.direction()) {
                long actualSeconds = (slot.startUtc().toEpochMilli()
                        - predecessor.endUtc().toEpochMilli()) / 1000;
                if (actualSeconds < clearanceSeconds) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "CLEARANCE_VIOLATION");
                    detail.put("sectionId", sectionId);
                    detail.put("opDate", slot.opDate().toString());
                    detail.put("scheduleKey", slot.scheduleKey());
                    detail.put("conflictingScheduleKey", predecessor.scheduleKey());
                    detail.put("actualSeconds", actualSeconds);
                    detail.put("requiredSeconds", clearanceSeconds);
                    violations.add(detail);
                }
            }
            if (predecessor == null || slot.endUtc().isAfter(predecessor.endUtc())) {
                predecessor = slot;
            }
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CLEARANCE_VIOLATION",
                    "新清空间隔使当前已发布集合非法", violations);
        }
    }

    /**
     * 幂等身份：操作、操作者、资源（区段）与全部参数（含期望版本）结构化取 SHA-256。
     */
    private String hashUpsert(String sectionId, SectionConfigRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_CONFIG_UPSERT);
        identity.put("operator", req.operator());
        identity.put("sectionId", sectionId);
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("clearanceSeconds", req.clearanceSeconds());
        return RequestIdentities.hash(objectMapper, identity);
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<SectionConfigResponse> replayIfPresent(String requestId, String hash) {
        return idemRepo.find(OP_CONFIG_UPSERT, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestId 已用于其他参数: " + requestId);
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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
