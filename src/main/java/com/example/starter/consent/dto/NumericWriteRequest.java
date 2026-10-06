package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 数值记录写入请求：向当前有效研究代次写入 0 至 long 最大值的整数数值。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param operator   操作者标识（合成字符串），参与幂等身份
 * @param subjectKey 主体标识（合成字符串）
 * @param recordKey  记录键，同一代内唯一
 * @param value      整数值，取值 0 至 9223372036854775807
 */
public record NumericWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Min(0) Long value) {
}
