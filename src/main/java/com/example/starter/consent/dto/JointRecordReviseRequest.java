package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 共同拥有记录修订请求：内容修订使提案版本加一并清空旧确认，最多修订 3 次。
 *
 * @param requestId      幂等请求标识
 * @param expectedVersion 期望的当前提案版本，用于乐观并发校验，不一致返回 409
 * @param payload        修订后的提案内容（合成字符串）
 */
public record JointRecordReviseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotBlank @Size(max = 8192) String payload) {
}
