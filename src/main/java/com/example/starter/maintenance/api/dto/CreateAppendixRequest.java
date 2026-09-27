package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建保养更正附录请求：完工后发现错误只能追加附录，不重开周期、不改写历史。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param createdBy 附录创建人
 * @param content   更正内容说明
 */
public record CreateAppendixRequest(
        @NotBlank String requestId,
        @NotBlank String createdBy,
        @NotBlank String content) {
}
