package com.example.starter.batch.dto;

import com.example.starter.batch.SequentialOutcome;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交合成序贯检验结果请求。
 * requestId 为全局幂等键：结构化身份含操作、资源（批次）、全部参数及期望版本
 * （sampleNumber 即期望的下一序号）；数值按规范值比较、文本原样比较，
 * 同键同参重放首次完整响应，同键改参返回 409，失败不占键。
 * operator 为操作者，非空；sampleNumber 从 1 连续递增，跳号或覆盖返回 409。
 */
public record SubmitSequentialResultRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "sampleNumber 不能为空")
        @Min(value = 1, message = "sampleNumber 必须从 1 开始连续递增") Integer sampleNumber,
        @NotNull(message = "outcome 不能为空，取值为 GOOD/BAD") SequentialOutcome outcome
) {
}
