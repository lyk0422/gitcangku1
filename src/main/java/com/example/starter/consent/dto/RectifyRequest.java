package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录更正请求：绑定主体、用途、代次、记录键与期望版本，追加完整新 payload 并使版本加一。
 *
 * @param requestId             幂等请求标识
 * @param subjectKey            主体标识（合成字符串）
 * @param purpose               用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch                 记录所属授权代次，必须为当前有效代次
 * @param recordKey             记录键
 * @param expectedRecordVersion 期望的当前记录版本号，必须等于当前版本，初版为 1
 * @param payload               完整新记录内容（合成字符串），最多 1000 字符
 */
public record RectifyRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String recordKey,
        @NotNull @Min(1) Integer expectedRecordVersion,
        @NotBlank @Size(max = 1000) String payload) {
}
