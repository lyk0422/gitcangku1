package com.example.starter.aging.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 接口请求对象。日期均为 UTC 自然日，时刻均为 UTC 瞬间。
 */
public final class ApiRequests {

    private ApiRequests() {
    }

    /** 创建批次请求：batchCode 全局唯一，entryAt 为入库 UTC 时刻。 */
    public record CreateBatchRequest(@NotBlank String batchCode, @NotNull Instant entryAt) {
    }

    /** 翻面确认请求：requestId 为幂等键，turnDate 为 UTC 自然日。 */
    public record TurnRequest(@NotBlank String requestId, @NotNull LocalDate turnDate,
                              @NotBlank String operator, String note) {
    }

    /** 测温追加请求：celsius 单位摄氏度。 */
    public record TempRequest(@NotNull LocalDate tempDate, @NotNull BigDecimal celsius,
                              @NotBlank String recordedBy) {
    }

    /** 替代测温记录。 */
    public record ReplacementTemp(@NotNull LocalDate tempDate, @NotNull BigDecimal celsius,
                                  @NotBlank String recordedBy) {
    }

    /** 测温作废请求：replacement 可空，非空时同事务追加替代记录。 */
    public record VoidRequest(@NotBlank String reason, ReplacementTemp replacement) {
    }

    /** 放行请求：requestId 为幂等键。 */
    public record ReleaseRequest(@NotBlank String requestId) {
    }
}