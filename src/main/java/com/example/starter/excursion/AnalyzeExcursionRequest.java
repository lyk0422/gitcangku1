package com.example.starter.excursion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 越限区间分析请求：引用一组当前版本观测，按阈值 H 与连续分钟数 N 判定持续越限区间。
 *
 * @param requestId          全局唯一请求标识（幂等去重键）
 * @param operator           操作者标识，非空
 * @param threshold          阈值 H，十进制字符串，最多三位小数；严格大于才算越限
 * @param consecutiveMinutes 连续分钟数 N，取值 2 至 5
 * @param references         引用观测列表，1 至 20 条，每条携带期望的当前版本号
 */
public record AnalyzeExcursionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Pattern(regexp = "-?\\d+(\\.\\d{1,3})?",
                message = "threshold must be a decimal string with at most 3 fraction digits") String threshold,
        @Min(2) @Max(5) int consecutiveMinutes,
        @NotNull @Size(min = 1, max = 20) List<@Valid ObservationRef> references) {

    /**
     * 单条引用：观测标识与其期望的当前版本号（乐观核验，非当前版本整次 409）。
     *
     * @param observationId 观测记录唯一标识
     * @param version       客户端看到的当前版本号
     */
    public record ObservationRef(@NotBlank @Size(max = 64) String observationId,
                                 @Min(1) int version) {
    }
}
