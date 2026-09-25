package com.example.starter.workblock.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消施工单请求。requestKey 为幂等键，operator 为取消操作者并参与幂等指纹。
 */
public record CancelWorkBlockRequest(@NotBlank String requestKey, @NotBlank String operator) {
}
