package com.example.starter.exposure.budget.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 预算预占确认/取消请求体，仅携带幂等键；预占单编号来自路径。
 *
 * @param requestId 写操作全局唯一幂等键
 */
public record BudgetReservationActionRequest(
        @NotBlank @Size(max = 64) String requestId
) {
}
