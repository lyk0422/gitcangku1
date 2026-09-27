package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登记材料批次请求。
 *
 * @param requestId    全局唯一请求标识（幂等键）
 * @param batchCode    材料批次编码，格式 ^[A-Za-z0-9][A-Za-z0-9-]{2,63}$
 * @param materialName 材料名称
 */
public record RegisterMaterialRequest(
        @NotBlank String requestId,
        @NotBlank String batchCode,
        @NotBlank String materialName) {
}
