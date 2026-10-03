package com.example.starter.allocation.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 取消分配请求。
 *
 * @param reason 取消原因，会固化到记录上供查询
 */
public record CancelRequest(@NotBlank String reason) {
}