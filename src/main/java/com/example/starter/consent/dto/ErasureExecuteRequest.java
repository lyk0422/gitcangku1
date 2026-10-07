package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 清除执行请求：推进指定代次的清除任务，每次按 recordKey 字典序最多清除 5 条旧代载荷。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回首次结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      待清除的授权代次，从 1 开始
 */
public record ErasureExecuteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch) {
}
