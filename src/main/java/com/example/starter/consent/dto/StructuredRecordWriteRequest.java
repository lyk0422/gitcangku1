package com.example.starter.consent.dto;

import java.util.Map;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 结构化记录写入请求：仅当前有效代次可写入，保存 1 至 10 个合成字符串字段。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一
 * @param fields     字段集合：1 至 10 个字段，字段名为英文字母数字下划线，每值最多 100 字符
 */
public record StructuredRecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Size(min = 1, max = 10) Map<String, String> fields) {
}
