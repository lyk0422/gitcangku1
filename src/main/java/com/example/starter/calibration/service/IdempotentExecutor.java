package com.example.starter.calibration.service;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.CalcLogEntry;
import com.example.starter.calibration.repo.CalcLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * calcKey 幂等执行组件（须在业务事务内调用，仓储默认加入当前事务）：
 * <ul>
 *   <li>首个事务插入占位行并持锁执行业务，成功提交时回填结果；业务失败随事务回滚，占位行撤销（不占键）。</li>
 *   <li>同键的并发/后续事务在占位行上等待；首个事务提交后重放其首次结果（状态码与响应体）。</li>
 *   <li>若持锁事务回滚导致行消失，则当前事务重新获得首次计算权。</li>
 * </ul>
 */
@Component
public class IdempotentExecutor {

    private final CalcLogRepository calcLogs;
    private final ObjectMapper objectMapper;

    public IdempotentExecutor(CalcLogRepository calcLogs, ObjectMapper objectMapper) {
        this.calcLogs = calcLogs;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行一个写操作。
     *
     * @param calcKey       计算指纹键（由测量/批次版本、环境、系数版本与全部输入规范化派生）
     * @param operation     操作类型
     * @param fingerprint   规范化输入指纹文本（落库供审计）
     * @param successStatus 首次成功的 HTTP 状态码（重放时沿用）
     * @param resultType    成功响应体类型，用于重放反序列化
     * @param action        首次计算逻辑，返回响应体
     * @param <T>           响应体类型
     */
    public <T> Outcome<T> run(String calcKey, String operation, String fingerprint, int successStatus,
                              Class<T> resultType, java.util.function.Supplier<T> action) {
        boolean acquired = acquire(calcKey, operation);
        for (;;) {
            if (acquired) {
                T result = action.get();
                complete(calcKey, successStatus, fingerprint, toJson(result));
                return new Outcome<>(successStatus, result, false);
            }
            CalcLogEntry entry = awaitReplay(calcKey).orElse(null);
            if (entry == null) {
                // 占位行已随持锁事务回滚而消失：重新竞争首次计算权。
                acquired = acquire(calcKey, operation);
                continue;
            }
            if (entry.httpStatus() > 0 && entry.resultJson() != null) {
                return new Outcome<>(entry.httpStatus(), fromJson(entry.resultJson(), resultType), true);
            }
            // 行锁已串行化，正常不会读到未回填占位行；防御性报错而非错误重放。
            throw ApiException.conflict("CALC_KEY_INFLIGHT", "相同计算正在进行中: " + calcKey);
        }
    }

    /**
     * 尝试取得某 calcKey 的首次计算权。
     */
    public boolean acquire(String calcKey, String operation) {
        return calcLogs.tryAcquire(calcKey, operation, Instant.now());
    }

    /**
     * 等待并读取同键首次结果；持锁事务回滚时返回 empty。
     */
    public java.util.Optional<CalcLogEntry> awaitReplay(String calcKey) {
        return calcLogs.findForUpdate(calcKey);
    }

    /**
     * 回填首次成功结果。
     */
    public void complete(String calcKey, int httpStatus, String fingerprint, String resultJson) {
        calcLogs.complete(calcKey, httpStatus, fingerprint, resultJson);
    }

    /**
     * 记录一次由唯一约束/状态机裁决（不做重放）的成功操作指纹，用于提交/放行/驳回的审计。
     * 失败路径不调用本方法，因而失败不占键。
     */
    public void recordSuccess(String calcKey, String operation, int httpStatus,
                              String fingerprint, Object result) {
        calcLogs.insertCompleted(calcKey, operation, httpStatus, fingerprint, toJson(result), Instant.now());
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw ApiException.badRequest("响应序列化失败: " + ex.getMessage());
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw ApiException.badRequest("首次结果重放反序列化失败: " + ex.getMessage());
        }
    }

    /**
     * 幂等执行结果。
     *
     * @param httpStatus 应返回的 HTTP 状态码（首次成功码或重放码）
     * @param body       响应体（首次结果或重放结果）
     * @param replayed   是否为重放（true 表示复用了同键首次结果）
     */
    public record Outcome<T>(int httpStatus, T body, boolean replayed) {
    }
}
