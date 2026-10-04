package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 未解决冲突登记器：三方合并检出冲突时在独立事务（REQUIRES_NEW）中登记 OPEN 记录，
 * 使冲突证据不随业务请求的回滚而撤销；同一 requestId 重复登记时静默忽略。
 */
@Service
public class ConflictRecorder {

    private final ConflictRepository conflictRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ConflictRecorder(ConflictRepository conflictRepository, ObjectMapper objectMapper, Clock clock) {
        this.conflictRepository = conflictRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记一条 OPEN 冲突，conflictId 取触发合并请求的 requestId。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOpenConflict(String observationId, String requestId, List<String> conflictFields) {
        try {
            conflictRepository.insertOpen(requestId, observationId,
                    writeFields(conflictFields), Instant.now(clock).truncatedTo(ChronoUnit.MILLIS));
        } catch (DuplicateKeyException ignored) {
            // 同一 requestId 的冲突已登记：保持首次证据不变
        }
    }

    private String writeFields(List<String> conflictFields) {
        try {
            return objectMapper.writeValueAsString(conflictFields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize conflict fields", e);
        }
    }
}