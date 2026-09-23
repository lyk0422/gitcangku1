package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入，属性必须落在用途处理范围内。
 *
 * @param requestId       幂等请求标识
 * @param subjectKey      主体标识（合成字符串）
 * @param purpose         用途代码
 * @param recordKey       记录键，同一代内唯一
 * @param payload         记录内容（合成字符串）
 * @param recordAttribute 记录属性（处理空间内的整数值），决定用途拆分时的唯一目标用途
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 64) String purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @NotNull Long recordAttribute) {
}
