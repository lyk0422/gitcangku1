package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;

/**
 * 登记授粉操作请求。
 *
 * @param requestId 幂等请求键：同键同参重放返回原结果，同键异参返回 409
 * @param pollenBatchNo 花粉批号，须为已登记且未停用的批次
 * @param technicianId 执行技术员编号
 */
public record CreateOperationRequest(
        @NotBlank String requestId,
        @NotBlank String pollenBatchNo,
        @NotBlank String technicianId) {
}