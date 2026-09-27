package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 材料批次登记请求。
 *
 * @param requestId     全局唯一请求标识（幂等键）
 * @param materialId    材料批次号，全局唯一
 * @param materialName  材料名称
 * @param unit          计量单位，登记后不可变
 */
public record MaterialRegisterRequest(
        @NotBlank String requestId,
        @NotBlank String materialId,
        @NotBlank String materialName,
        @NotBlank String unit) {
}
