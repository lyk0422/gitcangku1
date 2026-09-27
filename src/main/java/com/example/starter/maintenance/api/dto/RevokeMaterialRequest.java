package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销材料批次请求：撤销为终态，已撤销批次不可再被证据包引用。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param reason    撤销原因
 */
public record RevokeMaterialRequest(
        @NotBlank String requestId,
        @NotBlank String reason) {
}
