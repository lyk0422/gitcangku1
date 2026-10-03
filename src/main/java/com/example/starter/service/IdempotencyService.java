package com.example.starter.service;

import com.example.starter.domain.Rows.IdempotencyRow;
import com.example.starter.error.ConflictException;
import com.example.starter.repo.IdempotencyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 幂等执行器：同 requestId 同参重放返回首次响应，异参抛 409；业务失败回滚不占键。 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper,
                              Clock businessClock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = businessClock;
    }

    /** 执行带幂等保护的业务动作；action 返回的 DTO 将被序列化为响应报文。 */
    public IdempotentResponse execute(String requestId, String endpoint, String paramsHash,
                                      Supplier<Object> action) {
        Optional<IdempotencyRow> existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replay(requestId, paramsHash, existing.get());
        }
        try {
            return transactionTemplate.execute(status -> {
                Object result = action.get();
                String body = toJson(result);
                repository.insert(new IdempotencyRow(requestId, endpoint, paramsHash, 200,
                        body, Instant.now(clock)));
                return new IdempotentResponse(200, body);
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：另一事务已提交，读取其结果并按同参/异参处理
            IdempotencyRow stored = repository.find(requestId)
                    .orElseThrow(() -> e);
            return replay(requestId, paramsHash, stored);
        }
    }

    private IdempotentResponse replay(String requestId, String paramsHash, IdempotencyRow stored) {
        if (!stored.paramsHash().equals(paramsHash)) {
            throw new ConflictException("requestId 已使用且参数不一致: " + requestId);
        }
        return new IdempotentResponse(stored.responseStatus(), stored.responseBody());
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }
}