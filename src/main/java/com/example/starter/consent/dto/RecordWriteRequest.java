package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入；数据写入必须携带与所属代次登记区域一致的区域路由。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一
 * @param payload    记录内容（合成字符串）
 * @param regionCode 写入区域路由，必须与所属代次登记区域一致；代次未登记区域时必须为 NULL
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @Size(max = 64) String regionCode) {

    /**
     * 兼容构造：无区域路由的写入请求（仅适用于未登记区域的代次）。
     */
    public RecordWriteRequest(String requestId, String subjectKey, Purpose purpose,
                              String recordKey, String payload) {
        this(requestId, subjectKey, purpose, recordKey, payload, null);
    }
}
