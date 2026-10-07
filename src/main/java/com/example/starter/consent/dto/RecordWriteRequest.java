package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入；携带全局 requestId 与非空操作者，
 * 期望版本必须与当前有效代次一致，否则返回 409。
 *
 * @param requestId       幂等请求标识
 * @param subjectKey      主体标识（合成字符串）
 * @param purpose         用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey       记录键，同一代内唯一
 * @param payload         记录内容（合成字符串）；十进制按同值同参比较，文本原样保存
 * @param operator        操作者标识（非空）
 * @param expectedVersion 期望写入的授权代次，从 1 开始，须等于当前有效代次
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Min(1) Integer expectedVersion) {
}
