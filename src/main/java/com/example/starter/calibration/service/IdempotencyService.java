package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.IdempotentReplayException;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等服务：requestId 同参重放首次结果，异参 409，失败不占键。
 * 调用方须在事务内使用：begin 占键（插入占位行），操作成功后 complete 回填首次结果；
 * 业务失败导致事务回滚时占位行一并消失，因此失败不占键。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 占键并校验参数指纹。已存在同参记录时抛出 IdempotentReplayException 重放首次结果；
     * 异参抛出 409；并发同键时等待先提交事务后按同一规则裁决。
     *
     * @param requestId   幂等请求 ID（必填）
     * @param operation   操作类型
     * @param fingerprint 规范化参数串（参与 SHA-256）
     * @return 校验通过的 requestId
     */
    public String begin(String requestId, String operation, String fingerprint) {
        String key = Inputs.requireText(requestId, "requestId");
        String hash = sha256(operation + "|" + fingerprint);
        Optional<IdempotencyRecord> existing = repository.find(key);
        if (existing.isPresent()) {
            throw replayOrConflict(existing.get(), hash, key);
        }
        try {
            repository.insertPlaceholder(key, operation, hash, Instant.now(clock));
        } catch (DuplicateKeyException ex) {
            // 并发同事务键：先提交的事务已占键，读取其结果并按同参/异参裁决
            IdempotencyRecord committed = repository.find(key)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                            "requestId 并发冲突，请重试: " + key));
            throw replayOrConflict(committed, hash, key);
        }
        return key;
    }

    /**
     * 回填首次结果（须在 begin 后的同一事务内调用）。
     */
    public void complete(String requestId, int responseStatus, Object body) {
        try {
            repository.complete(requestId, responseStatus, objectMapper.writeValueAsString(body));
        } catch (Exception ex) {
            throw new IllegalStateException("序列化幂等响应失败", ex);
        }
    }

    private RuntimeException replayOrConflict(IdempotencyRecord record, String hash, String key) {
        if (record.paramsHash().equals(hash)) {
            return new IdempotentReplayException(record.responseStatus(), record.responseBody());
        }
        return ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已以不同参数使用: " + key);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}