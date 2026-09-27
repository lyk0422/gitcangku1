package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登记材料批次请求。批次编码格式 MAT- 开头加 4-16 位大写字母数字。
 *
 * @param requestId    全局唯一请求标识（幂等键）
 * @param batchCode    材料批次编码，全局唯一
 * @param description  材料描述（可空，null 表示不提供）
 */
public record RegisterMaterialRequest(
        @NotBlank String requestId,
        @NotBlank @Size(max = 32) String batchCode,
        @Size(max = 256) String description) {
}
