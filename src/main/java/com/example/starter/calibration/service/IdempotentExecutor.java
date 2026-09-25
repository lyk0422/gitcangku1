package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.CalcRecord;
import com.example.starter.calibration.repo.CalcRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * calcKey 幂等执行器。
 * 业务动作在同一事务内执行，成功时固化动作内产出的指纹内容（含测量/批次版本、环境、系数版本与全部输入）
 * 与响应 JSON；业务失败抛出则整事务回滚，calcKey 不被占用（失败不占键）。
 * 同 calcKey 重放：以首次成功响应重建指纹，与固化指纹一致则重放首次结果；不一致返回 409。
 * 并发同键按事务提交顺序裁决：后提交者唯一键冲突回滚后重放首次结果。
 */
@Component
public class IdempotentExecutor {

    private final CalcRecordRepository records;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(CalcRecordRepository records,
                             TransactionTemplate txTemplate,
                             ObjectMapper objectMapper) {
        this.records = records;
        this.txTemplate = txTemplate;
        this.objectMapper = objectMapper;
    }

    /** 幂等执行结果。 */
    public record Result<T>(T value, boolean replayed) {
    }

    /** 业务动作产出：响应值 + 固化指纹内容（动作内基于提交后状态构造）。 */
    public record Outcome<T>(T value, String fingerprintContent) {

        public static <T> Outcome<T> of(T value, String fingerprintContent) {
            return new Outcome<>(value, fingerprintContent);
        }
    }

    /**
     * 计算 SHA-256 指纹（十六进制）。
     */
    public static String fingerprint(String operation, String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((operation + "|" + content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 无 calcKey 时直接在事务内执行业务；有 calcKey 时按指纹重放或占用。
     *
     * @param calcKey          幂等键（空表示不启用）
     * @param operation       操作类型
     * @param action          业务动作（幂等事务内执行），返回响应与固化指纹内容
     * @param replayFingerprint 重放时基于首次成功响应（已反序列化）重建指纹内容（须与固化指纹一致才重放）
     * @param type            成功响应类型（用于重放反序列化）
     */
    public <T> Result<T> execute(String calcKey, String operation,
                                Supplier<Outcome<T>> action,
                                Function<T, String> replayFingerprint,
                                Class<T> type) {
        if (calcKey == null || calcKey.isBlank()) {
            Outcome<T> outcome = txTemplate.execute(status -> action.get());
            return new Result<>(outcome.value(), false);
        }
        String key = calcKey.trim();
        try {
            return txTemplate.execute(status -> runInTransaction(key, operation, action, replayFingerprint, type));
        } catch (DuplicateKeyException conflict) {
            return replayAfterLoss(key, operation, replayFingerprint, type);
        } catch (IdempotentConcurrentException conflict) {
            // 带 calcKey 的创建操作在业务唯一约束上落败（先提交事务已成功）：本事务已回滚，重放首次结果。
            return replayAfterLoss(key, operation, replayFingerprint, type);
        }
    }

    private <T> Result<T> replayAfterLoss(String key, String operation,
                                          Function<T, String> replayFingerprint, Class<T> type) {
        CalcRecord stored = records.findByKey(key)
                .orElseThrow(() -> ApiException.conflict("CALC_KEY_BUSY",
                        "calcKey 正被占用: " + key));
        T replayed = deserialize(stored.responseJson(), type);
        assertFingerprint(stored, operation, replayFingerprint.apply(replayed));
        return new Result<>(replayed, true);
    }

    private <T> Result<T> runInTransaction(String key, String operation,
                                           Supplier<Outcome<T>> action,
                                           Function<T, String> replayFingerprint,
                                           Class<T> type) {
        var existing = records.findByKey(key);
        if (existing.isPresent()) {
            T replayed = deserialize(existing.get().responseJson(), type);
            assertFingerprint(existing.get(), operation, replayFingerprint.apply(replayed));
            return new Result<>(replayed, true);
        }
        Outcome<T> outcome = action.get();
        String fp = fingerprint(operation, outcome.fingerprintContent());
        String json;
        try {
            json = objectMapper.writeValueAsString(outcome.value());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        records.insert(new CalcRecord(key, operation, fp, json, Instant.now()));
        return new Result<>(outcome.value(), false);
    }

    private void assertFingerprint(CalcRecord record, String operation, String currentFingerprintContent) {
        if (!record.operation().equals(operation)) {
            throw ApiException.conflict("IDEMPOTENCY_OPERATION_MISMATCH",
                    "calcKey 已用于其他操作: " + record.calcKey());
        }
        if (!record.fingerprint().equals(fingerprint(operation, currentFingerprintContent))) {
            throw ApiException.conflict("IDEMPOTENCY_FINGERPRINT_MISMATCH",
                    "calcKey 相同但请求指纹不一致: " + record.calcKey());
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
