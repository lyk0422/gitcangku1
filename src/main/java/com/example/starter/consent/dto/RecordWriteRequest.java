package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入；可指定子范围，缺省归入默认子范围。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一
 * @param scopeKey   子范围标识，可选；为空时归入默认子范围
 * @param payload    记录内容（合成字符串）
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @Size(max = 128) String scopeKey,
        @NotBlank @Size(max = 8192) String payload) {

    /**
     * 兼容构造：不指定子范围，归入默认子范围。
     */
    public RecordWriteRequest(String requestId, String subjectKey, Purpose purpose,
                              String recordKey, String payload) {
        this(requestId, subjectKey, purpose, recordKey, null, payload);
    }
}
