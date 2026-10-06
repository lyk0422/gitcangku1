package com.example.starter.maintenance.service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EquipmentRepository.IdempotencyRow;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等去重：requestId 全局唯一；同键同参重放原成功结果，同键异参返回 409。
 * 去重记录与业务变更在同一事务提交；业务失败抛异常回滚，不占键。
 * 调用方须先取得设备行锁再调用本服务，保证同设备并发重放的一致性。
 */
@Service
public class IdempotencyService {

    private final EquipmentRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(EquipmentRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 写操作身份：操作类型 + 结构化参数指纹。
     *
     * @param operation   操作类型
     * @param fingerprint 结构化 JSON 指纹（操作、操作者、资源、全部参数与期望版本）
     */
    public record WriteIdentity(String operation, String fingerprint) {
    }

    /**
     * 构造写操作身份。指纹为结构化 JSON（键固定、值按类型编码），
     * 避免字符串拼接在参数含分隔符时发生碰撞；params 按插入顺序序列化，保证确定性。
     *
     * @param operation 操作类型
     * @param resource  资源标识（如 equipment/{id}/readings/{readingId}）
     * @param operator  操作者标识（可为 null）
     * @param params    全部业务参数（含期望版本，不含 requestId）
     */
    public WriteIdentity identity(String operation, String resource, String operator,
                                  Map<String, Object> params) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("operation", operation);
        structured.put("operator", operator);
        structured.put("resource", resource);
        structured.put("params", new LinkedHashMap<>(params));
        try {
            return new WriteIdentity(operation, objectMapper.writeValueAsString(structured));
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    /**
     * 在调用方事务内执行幂等写操作。
     *
     * @param requestId   全局唯一请求标识
     * @param operation   操作类型
     * @param fingerprint 业务参数指纹（不含 requestId）
     * @param type        响应类型（用于重放反序列化）
     * @param action      业务动作，仅首次执行；抛异常则整体回滚、不占键
     */
    public <T> T execute(String requestId, String operation, String fingerprint,
                         Class<T> type, Supplier<T> action) {
        Optional<IdempotencyRow> existing = repository.findIdempotency(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, fingerprint, type);
        }
        T result = action.get();
        repository.insertIdempotency(requestId, operation, fingerprint, writeJson(result), clock.instant());
        return result;
    }

    /** 事务外重放：用于并发唯一键冲突后的补偿查询。 */
    public <T> T replayExisting(String requestId, String operation, String fingerprint, Class<T> type) {
        Optional<IdempotencyRow> existing = repository.findIdempotency(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        return replay(existing.get(), operation, fingerprint, type);
    }

    private <T> T replay(IdempotencyRow row, String operation, String fingerprint, Class<T> type) {
        if (!row.operation().equals(operation) || !row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 已被不同参数的请求使用");
        }
        return readJson(row.responseBody(), type);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readJson(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等重放反序列化失败", e);
        }
    }
}
