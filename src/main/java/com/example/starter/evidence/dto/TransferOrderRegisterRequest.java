package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 移交令版本登记请求（合成数据初始化入口）。有效期按 UTC 左闭右开解释。
 *
 * @param orderVersion 移交令版本
 * @param validFrom    有效期起点（UTC，左闭）
 * @param validTo      有效期终点（UTC，右开），须晚于 validFrom
 */
public record TransferOrderRegisterRequest(
        @NotBlank @Size(max = 64) String orderVersion,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validTo) {
}
