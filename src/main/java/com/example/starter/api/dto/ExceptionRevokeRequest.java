package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销已生效豁免的请求：仅影响此后的新锁定，不改写历史锁文件。
 */
public record ExceptionRevokeRequest(@NotBlank @Size(max = 128) String revoker) {
}
