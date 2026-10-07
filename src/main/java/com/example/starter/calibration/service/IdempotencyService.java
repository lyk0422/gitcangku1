package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等去重服务：写请求携带非空 requestId 时启用（操作者必须非空）。
 * 指纹为结构化 JSON（操作、资源、全参数、期望版本），字段无碰撞；
 * 十进制参数同值同参（数值规范化），文本参数原样比较。
 * 占位记录与业务在同一事务提交：业务失败整体回滚（失败不占键）；
 * 并发同键由主键串行化，冲突方重放首次完整响应，不暴露唯一键错误。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository records;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行写操作。requestId 为空时直接执行（不启用幂等）；
     * 非空时要求操作者非空，重放相同指纹返回首次完整响应，指纹不同返回 409。
     */
    @Transactional
    public ResponseEntity<String> execute(String operation, String resource, String requestId,
                                          String actor, Map<String, Object> params,
                                          HttpStatus successStatus, Supplier<Object> business) {
        if (requestId == null || requestId.isBlank()) {
            return respond(successStatus, writeJson(business.get()));
        }
        String id = requestId.trim();
        if (actor == null || actor.isBlank()) {
            throw ApiException.badRequest("启用幂等的写请求必须携带非空操作者");
        }
        String fingerprint = fingerprint(operation, resource, params);
        try {
            records.insertPlaceholder(id, actor.trim(), fingerprint, Instant.now());
        } catch (DuplicateKeyException ex) {
            return replay(id, fingerprint);
        }
        String body = writeJson(business.get());
        records.complete(id, successStatus.value(), body);
        return respond(successStatus, body);
    }

    /**
     * 十进制参数规范化：同值同参（如 "1.0" 与 "1.00" 指纹一致），零统一为 "0"；
     * 无法解析的值原样保留（由业务校验拒绝）。
     */
    public static String normalizeDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            if (value.compareTo(BigDecimal.ZERO) == 0) {
                return "0";
            }
            return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return raw;
        }
    }

    private ResponseEntity<String> replay(String id, String fingerprint) {
        IdempotencyRecord record = records.findById(id)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "请求 ID 冲突且首次记录不可用: " + id));
        if (!record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 携带不同参数: " + id);
        }
        return ResponseEntity.status(record.responseStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(record.responseBody());
    }

    private String fingerprint(String operation, String resource, Map<String, Object> params) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("operation", operation);
        structured.put("resource", resource);
        structured.put("params", params);
        return writeJson(structured);
    }

    private static ResponseEntity<String> respond(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }
}
