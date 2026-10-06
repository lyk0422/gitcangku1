package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.ResolutionResponse;
import com.example.starter.calibration.api.dto.UpsertResolutionRequest;
import com.example.starter.calibration.model.ResolutionConfig;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.example.starter.calibration.repo.IdempotencyRepository.IdempotencyRecord;
import com.example.starter.calibration.repo.ResolutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 仪器分辨率格点配置服务：新建（版本从 1 起）、按 expectedVersion 乐观并发更新、查询。
 * 写入按 requestId 幂等：同键同参重放首次完整响应，异参 409，失败不占键；
 * 去重记录与业务写入同事务提交，并发竞争不泄露唯一键异常。
 */
@Service
public class ResolutionService {

    /** 幂等记录的操作类型。 */
    static final String OPERATION = "UPSERT_RESOLUTION";

    /**
     * 幂等写入结果：HTTP 状态码与响应体，重放时原样返回。
     *
     * @param httpStatus HTTP 状态码（新建 201，更新 200）
     * @param body       配置响应体
     */
    public record StoredResponse(int httpStatus, ResolutionResponse body) {
    }

    private final ResolutionRepository resolutions;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public ResolutionService(ResolutionRepository resolutions,
                             IdempotencyRepository idempotency,
                             ObjectMapper objectMapper) {
        this.resolutions = resolutions;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 新建或更新配置。expectedVersion 缺省表示新建；更新时须等于当前版本，否则 409。
     * 换版不回算既有测量；与测量提交通过配置行锁串行化，双方看到一致值。
     */
    @Transactional
    public StoredResponse upsert(String instrumentId, UpsertResolutionRequest request, String actor) {
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String resource = Inputs.requireText(instrumentId, "instrumentId");
        String requestId = Inputs.requireText(request == null ? null : request.requestId(), "requestId");
        BigDecimal origin = Inputs.requireDecimal(request.origin(), "origin");
        BigDecimal step = Inputs.requireDecimal(request.step(), "step");
        if (step.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("step 必须为正数");
        }
        Integer expectedVersion = request.expectedVersion();
        if (expectedVersion != null && expectedVersion < 1) {
            throw ApiException.badRequest("expectedVersion 必须为不小于 1 的整数");
        }
        String paramsJson = buildParamsJson(origin, step, expectedVersion);

        // 先占位声明幂等键：业务失败时整个事务回滚，失败不占键
        try {
            idempotency.insert(new IdempotencyRecord(requestId, OPERATION, operator, resource,
                    paramsJson, "", 0, Instant.now()));
        } catch (DuplicateKeyException ex) {
            // 并发或重复同键：读取已提交记录，同参重放、异参 409，不泄露唯一键异常
            return replayOrConflict(idempotency.findByRequestId(requestId).orElseThrow(),
                    operator, resource, paramsJson);
        }

        int httpStatus;
        if (expectedVersion == null) {
            try {
                resolutions.insert(resource, origin, step, operator, Instant.now());
            } catch (DuplicateKeyException ex) {
                throw ApiException.conflict("RESOLUTION_ALREADY_EXISTS",
                        "仪器已存在分辨率配置，请携带 expectedVersion 更新: " + resource);
            }
            httpStatus = 201;
        } else {
            if (resolutions.findByInstrument(resource).isEmpty()) {
                throw ApiException.notFound("仪器尚未配置分辨率: " + resource);
            }
            int updated = resolutions.updateWithVersion(resource, expectedVersion,
                    origin, step, operator, Instant.now());
            if (updated == 0) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "分辨率配置版本冲突: instrument=" + resource);
            }
            httpStatus = 200;
        }

        ResolutionResponse body = DtoMapper.toResponse(
                resolutions.findByInstrument(resource).orElseThrow());
        return finish(requestId, httpStatus, body);
    }

    /**
     * 查询仪器当前分辨率配置；未配置返回 404。
     */
    @Transactional(readOnly = true)
    public ResolutionResponse get(String instrumentId) {
        String resource = Inputs.requireText(instrumentId, "instrumentId");
        return DtoMapper.toResponse(resolutions.findByInstrument(resource)
                .orElseThrow(() -> ApiException.notFound("仪器尚未配置分辨率: " + resource)));
    }

    /** 结构化参数 JSON：字段独立，避免分隔符碰撞。 */
    private String buildParamsJson(BigDecimal origin, BigDecimal step, Integer expectedVersion) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("origin", origin.toPlainString());
        node.put("step", step.toPlainString());
        if (expectedVersion == null) {
            node.putNull("expectedVersion");
        } else {
            node.put("expectedVersion", expectedVersion);
        }
        return node.toString();
    }

    /** 同键同参重放首次完整响应；异参 409。身份要素分列比较，参数按 JSON 树结构化比较。 */
    private StoredResponse replayOrConflict(IdempotencyRecord record, String operator,
                                            String resource, String paramsJson) {
        boolean sameIdentity = record.operation().equals(OPERATION)
                && record.actor().equals(operator)
                && record.resource().equals(resource)
                && sameJson(record.paramsJson(), paramsJson);
        if (!sameIdentity) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_CONFLICT",
                    "请求 ID 已用于不同参数的写入: " + record.requestId());
        }
        try {
            return new StoredResponse(record.httpStatus(),
                    objectMapper.readValue(record.responseJson(), ResolutionResponse.class));
        } catch (Exception ex) {
            throw new IllegalStateException("幂等记录响应损坏: " + record.requestId(), ex);
        }
    }

    private boolean sameJson(String left, String right) {
        try {
            JsonNode a = objectMapper.readTree(left);
            JsonNode b = objectMapper.readTree(right);
            return a.equals(b);
        } catch (Exception ex) {
            return false;
        }
    }

    /** 业务成功后补写响应快照，与业务写入同事务提交。 */
    private StoredResponse finish(String requestId, int httpStatus, ResolutionResponse body) {
        try {
            idempotency.updateResponse(requestId, objectMapper.writeValueAsString(body), httpStatus);
        } catch (Exception ex) {
            throw new IllegalStateException("无法序列化幂等响应快照: " + requestId, ex);
        }
        return new StoredResponse(httpStatus, body);
    }
}
