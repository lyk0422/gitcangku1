package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 数值记录写入请求：向当前有效研究代次写入 0 至 long 最大值的整数数值。
 *
 * @param requestId     幂等请求标识，同 requestId 同参数重放返回首次响应
 * @param operator      操作者标识，参与幂等身份结构化比较
 * @param subjectKey    目标主体标识（合成字符串），用途固定为 RESEARCH 研究
 * @param recordKey     新记录键，同一代内唯一，已存在返回 409
 * @param value         数值，0 至 long 最大值
 * @param expectedEpoch 期望的目标主体当前研究代次，不一致返回 409
 */
public record NumericWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Min(0) Long value,
        @NotNull @Min(1) Integer expectedEpoch) {
}
