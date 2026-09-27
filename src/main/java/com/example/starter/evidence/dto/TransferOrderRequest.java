package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 移交令登记请求。有效期按 UTC 左闭右开 [validFrom, validTo) 解释。
 *
 * @param commandKey     幂等命令键
 * @param orderVersion   移交令版本，全局唯一
 * @param sourceCaseKey  令适用的来源案件键
 * @param targetCaseKey  令适用的目标案件键
 * @param validFrom      有效期起点（含），UTC，ISO-8601 格式
 * @param validTo        有效期终点（不含），UTC，ISO-8601 格式，须晚于 validFrom
 */
public record TransferOrderRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey,
        @NotNull LocalDateTime validFrom,
        @NotNull LocalDateTime validTo) {
}
