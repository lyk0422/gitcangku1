package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 共同拥有记录新增请求：指定两个不同主体的当前研究授权代次与合成内容，生成版本 1 提案。
 *
 * @param requestId   幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKeyA 主体A标识（合成字符串），必须与 subjectKeyB 不同
 * @param subjectKeyB 主体B标识（合成字符串），必须与 subjectKeyA 不同
 * @param epochA      主体A当前研究授权代次，从 1 开始，须与当前有效代次一致
 * @param epochB      主体B当前研究授权代次，从 1 开始，须与当前有效代次一致
 * @param recordKey   共同记录键，全局唯一
 * @param payload     提案内容（合成字符串）
 */
public record JointRecordCreateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKeyA,
        @NotBlank @Size(max = 128) String subjectKeyB,
        @NotNull @Min(1) Integer epochA,
        @NotNull @Min(1) Integer epochB,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload) {
}
