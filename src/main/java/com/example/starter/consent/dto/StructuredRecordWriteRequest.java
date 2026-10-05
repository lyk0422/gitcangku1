package com.example.starter.consent.dto;

import java.util.Map;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 结构化记录写入请求：仅当前有效代次可写入。
 *
 * @param requestId  幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一
 * @param fields     结构化字段，1 至 10 个；字段名为英文字母、数字、下划线（1-64 字符），每值最多 100 字符
 */
public record StructuredRecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Size(min = 1, max = 10) Map<String, String> fields) {
}
