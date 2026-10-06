package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.ResolutionConfigRequest;
import com.example.starter.calibration.api.dto.ResolutionConfigResponse;
import com.example.starter.calibration.model.ResolutionConfig;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.example.starter.calibration.repo.ResolutionConfigRepository;

/**
 * 仪器分辨率格点配置服务：创建/更新（expectedVersion 乐观版本，版本从 1 起）、查询。
 * 写入按 requestId 幂等：身份指纹为操作/操作者/资源/全部参数/期望版本的结构化 JSON，
 * 同键同参重放首次完整响应，异参 409，失败不占键；业务与去重记录同一事务原子提交。
 */
@Service
public class ResolutionConfigService {

    /** 幂等身份中的操作名。 */
    private static final String OPERATION = "RESOLUTION_UPSERT";

    /**
     * 写入结果：HTTP 状态码（201 创建 / 200 更新，重放时为首响状态）与响应体。
     *
     * @param status   HTTP 状态码
     * @param response 配置响应
     */
    public record ResolutionResult(int status, ResolutionConfigResponse response) {
    }

    private final ResolutionConfigRepository resolutions;
    private final IdempotencyRepository idempotency;
    private final CertificateRepository certificates;
    private final ObjectMapper objectMapper;

    public ResolutionConfigService(ResolutionConfigRepository resolutions,
                                   IdempotencyRepository idempotency,
                                   CertificateRepository certificates,
                                   ObjectMapper objectMapper) {
        this.resolutions = resolutions;
        this.idempotency = idempotency;
        this.certificates = certificates;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建或更新分辨率配置。expectedVersion 必须等于当前版本（无配置时为 0），否则 409；
     * 仪器级行锁串行化并发变更；requestId 幂等去重与业务写入原子提交。
     */
    @Transactional
    public ResolutionResult upsert(String instrumentIdRaw, ResolutionConfigRequest request, String actorRaw) {
        String instrumentId = Inputs.requireText(instrumentIdRaw, "instrumentId");
        String actor = Inputs.requireText(actorRaw, "X-Actor-Id");
        String requestId = Inputs.requireText(request == null ? null : request.requestId(), "requestId");
        BigDecimal step = Inputs.requireDecimal(request.step(), "step");
        BigDecimal origin = Inputs.requireDecimal(request.origin(), "origin");
        if (step.signum() <= 0) {
            throw ApiException.badRequest("step 必须为正数");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw ApiException.badRequest("expectedVersion 必须为不小于 0 的整数");
        }
        int expectedVersion = request.expectedVersion();
        String fingerprint = fingerprint(actor, instrumentId, step, origin, expectedVersion);

        certificates.lockInstrument(instrumentId);
        Optional<IdempotencyRepository.StoredRecord> stored = idempotency.findByRequestId(requestId);
        if (stored.isPresent()) {
            return replay(requestId, fingerprint, stored.get());
        }

        Instant now = Instant.now();
        Optional<ResolutionConfig> current = resolutions.findByInstrument(instrumentId);
        ResolutionConfig saved;
        int status;
        if (current.isEmpty()) {
            if (expectedVersion != 0) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "仪器尚未配置分辨率，expectedVersion 必须为 0: " + instrumentId);
            }
            ResolutionConfig created = new ResolutionConfig(
                    0L, instrumentId, step, origin, 1, actor, now, now);
            long id = resolutions.insert(created);
            saved = resolutions.findByInstrument(instrumentId).orElseThrow();
            status = 201;
        } else {
            ResolutionConfig existing = current.get();
            if (existing.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "配置版本冲突: 当前版本 " + existing.version() + ", 期望 " + expectedVersion);
            }
            ResolutionConfig updated = new ResolutionConfig(
                    existing.id(), instrumentId, step, origin, existing.version() + 1, actor,
                    existing.createdAt(), now);
            resolutions.update(updated);
            saved = resolutions.findByInstrument(instrumentId).orElseThrow();
            status = 200;
        }

        ResolutionConfigResponse response = DtoMapper.toResponse(saved);
        String body = toJson(response);
        try {
            idempotency.insert(requestId, fingerprint, status, body, now);
        } catch (DuplicateKeyException ex) {
            // 并发同键（跨仪器）竞争：整事务回滚，不泄露唯一键异常
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已被其他请求占用: " + requestId);
        }
        return new ResolutionResult(status, response);
    }

    /**
     * 查询仪器当前分辨率配置；未配置返回 404（未配置仪器沿用原规则）。
     */
    @Transactional(readOnly = true)
    public ResolutionConfigResponse get(String instrumentIdRaw) {
        String instrumentId = Inputs.requireText(instrumentIdRaw, "instrumentId");
        return resolutions.findByInstrument(instrumentId)
                .map(DtoMapper::toResponse)
                .orElseThrow(() -> ApiException.notFound("仪器未配置分辨率: " + instrumentId));
    }

    /** 同键重放：指纹一致返回首次完整响应，异参 409。 */
    private ResolutionResult replay(String requestId, String fingerprint,
                                    IdempotencyRepository.StoredRecord stored) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "requestId 已存在且请求参数不一致: " + requestId);
        }
        try {
            return new ResolutionResult(stored.responseStatus(),
                    objectMapper.readValue(stored.responseBody(), ResolutionConfigResponse.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("幂等记录响应反序列化失败: " + requestId, ex);
        }
    }

    /** 结构化身份指纹：操作/操作者/资源/全部参数/期望版本，避免分隔符碰撞。 */
    private String fingerprint(String actor, String instrumentId, BigDecimal step,
                               BigDecimal origin, int expectedVersion) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operation", OPERATION);
        node.put("actor", actor);
        node.put("resource", instrumentId);
        node.put("step", step.stripTrailingZeros().toPlainString());
        node.put("origin", origin.stripTrailingZeros().toPlainString());
        node.put("expectedVersion", expectedVersion);
        return node.toString();
    }

    private String toJson(ResolutionConfigResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("配置响应序列化失败", ex);
        }
    }
}
