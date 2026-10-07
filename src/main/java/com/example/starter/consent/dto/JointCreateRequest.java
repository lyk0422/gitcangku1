package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 共同研究记录创建请求：指定两个不同主体（各自当前研究代次在创建时冻结）与合成 payload。
 *
 * @param requestId 幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param recordKey 共同记录键，全局唯一
 * @param subjectA  主体A标识（合成字符串）
 * @param subjectB  主体B标识（合成字符串），必须与主体A不同
 * @param payload   提案内容（合成字符串）
 */
public record JointCreateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 128) String subjectA,
        @NotBlank @Size(max = 128) String subjectB,
        @NotBlank @Size(max = 8192) String payload) {
}
