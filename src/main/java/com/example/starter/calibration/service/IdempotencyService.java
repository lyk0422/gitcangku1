package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;

/**
 * 幂等执行核心：与业务写入同一事务提交去重记录。
 * 身份 = 操作 + 操作者 + 资源 + 结构化参数 JSON（含期望版本），分字段存储避免分隔符碰撞；
 * 同键同参重放首次完整响应，异参 409；业务失败回滚不占键。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 在单事务内执行：先插入占位记录（主键阻塞并发同键请求，对方提交后转为重放），
     * 再执行业务并写回完整响应，随业务一起提交；业务失败回滚则占位消失，失败不占键。
     */
    @Transactional
    public <T> ResponseEntity<String> execute(String requestId, String operation, String operator,
                                              String resource, Object params, HttpStatus status,
                                              Supplier<T> action) {
        String paramsJson = toJson(params);
        try {
            repository.insert(new IdempotencyRecord(requestId, operation, operator, resource,
                    paramsJson, 0, "", Instant.now()));
        } catch (DuplicateKeyException ex) {
            // 同键已提交（含并发竞争后）：按身份比较，重放首次完整响应或报 409
            IdempotencyRecord record = repository.findById(requestId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "requestId 冲突且未找到已提交记录: " + requestId));
            return replay(record, operation, operator, resource, paramsJson);
        }
        T body = action.get();
        String responseBody = toJson(body);
        repository.complete(requestId, status.value(), responseBody);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(responseBody);
    }

    /**
     * 并发同键竞争后的补偿：本事务已回滚，读取竞争者提交的记录，按身份比较后重放或报 409。
     */
    @Transactional(readOnly = true)
    public ResponseEntity<String> replayAfterRace(String requestId, String operation, String operator,
                                                  String resource, Object params) {
        IdempotencyRecord record = repository.findById(requestId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "requestId 冲突且未找到已提交记录: " + requestId));
        return replay(record, operation, operator, resource, toJson(params));
    }

    private ResponseEntity<String> replay(IdempotencyRecord record, String operation, String operator,
                                          String resource, String paramsJson) {
        if (record.operation().equals(operation)
                && record.operator().equals(operator)
                && record.resource().equals(resource)
                && jsonEquals(record.paramsJson(), paramsJson)) {
            return ResponseEntity.status(record.responseStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(record.responseBody());
        }
        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已存在但请求身份不一致: " + record.requestId());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化失败", ex);
        }
    }

    /** 结构化比较：解析为 JSON 树后按结构判等，不做字符串拼接比较。 */
    private boolean jsonEquals(String left, String right) {
        try {
            JsonNode a = objectMapper.readTree(left);
            JsonNode b = objectMapper.readTree(right);
            return a.equals(b);
        } catch (Exception ex) {
            return false;
        }
    }
}
