package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey  记录键，同一代内唯一（跨全部子范围唯一）
 * @param payload    记录内容（合成字符串）
 * @param scopeKey   所属子范围标识，可空：为空归入默认子范围；非空时该子范围须在本 epoch 内存在且有效，
 *                   写入会使其自动创建（带非空标签时见 label）
 * @param label      当 scopeKey 非空且子范围尚不存在时使用的非空标签；子范围已存在时忽略
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @Size(max = 128) String scopeKey,
        @Size(max = 256) String label) {

    /**
     * 兼容无子范围的写入：等价于归入默认子范围。
     */
    public RecordWriteRequest(String requestId, String subjectKey, Purpose purpose,
                              String recordKey, String payload) {
        this(requestId, subjectKey, purpose, recordKey, payload, null, null);
    }
}
