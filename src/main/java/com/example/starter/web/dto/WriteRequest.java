package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求，写入当前有效代次。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 或 PERSONALIZATION
 * @param recordKey  记录键，同一代内唯一
 * @param payload    记录内容（合成字符串）
 */
public record WriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 2048) String payload) {
}
