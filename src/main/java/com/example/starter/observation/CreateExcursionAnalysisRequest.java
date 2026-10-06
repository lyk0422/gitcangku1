package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 持续越限区间分析请求：引用一组分钟观测的当前版本，按阈值 H 与连续分钟数 N 判定区间。
 *
 * @param requestId          全局唯一请求标识（幂等去重键）
 * @param operator           操作者标识（非空）
 * @param references         引用的分钟观测（observationId + 期望当前版本），1 至 20 条，站号必须一致
 * @param threshold          越限阈值 H，十进制字符串，最多三位小数；严格大于才算越限
 * @param consecutiveMinutes 连续越限分钟数 N，取值 2 至 5
 */
public record CreateExcursionAnalysisRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 20) List<@Valid ObservationRef> references,
        @NotBlank @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?", message = "threshold must be a decimal string with at most 3 fraction digits")
        String threshold,
        @NotNull @Min(2) @Max(5) Integer consecutiveMinutes) {

    /**
     * 单条引用：分钟观测标识与其当前版本号（非当前版本拒绝）。
     *
     * @param observationId 分钟观测记录唯一标识
     * @param version       引用的当前版本号
     */
    public record ObservationRef(
            @NotBlank @Size(max = 64) String observationId,
            @NotNull @Min(1) Integer version) {
    }
}
