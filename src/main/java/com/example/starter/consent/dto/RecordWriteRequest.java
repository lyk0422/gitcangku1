package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入。
 *
 * @param requestId      幂等请求标识
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途代码，须为用途目录中处于 ACTIVE 状态的用途
 * @param recordKey      记录键，同一代内唯一
 * @param payload        记录内容（合成字符串）
 * @param attributeValue 记录属性取值，用途拆分迁移时据此确定唯一目标用途；为空表示属性缺失
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @Size(max = 256) String attributeValue) {

    /**
     * 兼容不含记录属性的旧请求构造。
     */
    public RecordWriteRequest(String requestId, String subjectKey, String purpose,
                              String recordKey, String payload) {
        this(requestId, subjectKey, purpose, recordKey, payload, null);
    }
}
