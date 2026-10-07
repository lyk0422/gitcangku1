package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 共同研究记录修订请求：内容修订使提案版本加一并清空旧确认，最多修订 3 次。
 *
 * @param requestId       幂等请求标识
 * @param payload         修订后的提案内容（合成字符串）
 * @param expectedVersion 期望的当前提案版本，不匹配返回 409
 */
public record JointReviseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 8192) String payload,
        @NotNull @Min(1) Integer expectedVersion) {
}
