package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 数值记录写入请求：仅当前有效代次可写入，值为 0-1000 的非负整数。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一（与字符串记录共用键空间）
 * @param value      数值内容，0-1000 的非负整数
 */
public record NumericRecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Min(0) @Max(1000) Integer value) {
}
