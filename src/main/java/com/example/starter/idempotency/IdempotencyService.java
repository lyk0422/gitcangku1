package com.example.starter.idempotency;

import com.example.starter.db.IdempotencyRepository;
import com.example.starter.db.IdempotencyRow;
import com.example.starter.domain.Actor;
import com.example.starter.error.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写操作幂等执行器。
 *
 * <p>每个 requestId 先插入占位去重记录（唯一约束），业务成功后在同一事务内写回原成功响应：
 * 同键同操作者、同角色、同参数重放时原样返回首次结果；任一不同则 409。
 * 业务异常导致事务回滚时占位记录一并回滚，失败不占用 requestId。
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
     * 在幂等保护下执行写操作。
     *
     * @param actor      操作者（含角色，参与幂等参数比对）
     * @param requestId  全局唯一请求编号
     * @param operation  操作类型
     * @param paramsHash 归一化业务参数指纹
     * @param action     首次执行时的业务动作
     * @return 首次执行或重放结果
     */
    @Transactional
    public WriteOutcome execute(Actor actor, String requestId, OperationType operation,
                                String paramsHash, WriteAction action) {
        IdempotencyRow placeholder = new IdempotencyRow(
                requestId,
                actor.actorId(),
                actor.role().name(),
                operation.name(),
                paramsHash,
                0,
                null,
                Instant.now());
        try {
            repository.insert(placeholder);
        } catch (DuplicateKeyException duplicate) {
            return replay(actor, requestId, operation, paramsHash);
        }

        IdempotencyService.BusinessResult result = action.run();
        String responseJson = toJson(result.body());
        repository.saveResponse(requestId, result.status(), responseJson);
        return new WriteOutcome(result.status(), responseJson, false);
    }

    private WriteOutcome replay(Actor actor, String requestId, OperationType operation, String paramsHash) {
        IdempotencyRow existing = repository.findByRequestId(requestId)
                .orElseThrow(() -> new ConflictException("idempotent request is being processed"));
        if (existing.responseStatus() == 0 || existing.responseBody() == null) {
            // 仅可能发生在同键并发：首请求事务尚未提交
            throw new ConflictException("idempotent request is being processed");
        }
        boolean sameCaller = existing.actorId().equals(actor.actorId())
                && existing.role().equals(actor.role().name());
        boolean sameParams = existing.operation().equals(operation.name())
                && existing.paramsHash().equals(paramsHash);
        if (!sameCaller || !sameParams) {
            throw new ConflictException("requestId was already used with different parameters");
        }
        return new WriteOutcome(existing.responseStatus(), existing.responseBody(), true);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize write response", ex);
        }
    }

    /** 受保护业务动作：返回 HTTP 状态码与可序列化为 JSON 的响应对象。 */
    @FunctionalInterface
    public interface WriteAction {
        BusinessResult run();
    }

    /** 业务动作结果。 */
    public record BusinessResult(int status, Object body) {
    }
}
