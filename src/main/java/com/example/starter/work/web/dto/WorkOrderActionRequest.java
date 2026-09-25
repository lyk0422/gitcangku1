package com.example.starter.work.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消施工单请求，携带幂等键与操作者。
 */
public record WorkOrderActionRequest(
        @NotBlank String requestKey,
        @NotBlank String operator) {
}
