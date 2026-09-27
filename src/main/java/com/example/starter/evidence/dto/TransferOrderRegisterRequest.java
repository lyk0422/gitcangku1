package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 移交令登记请求。令版本全局唯一，登记后为 VALID，可用于匹配案件对的跨案移交。
 *
 * @param commandKey    幂等命令键
 * @param orderVersion  移交令版本
 * @param sourceCaseKey 来源案件键
 * @param targetCaseKey 目标案件键，须与来源案件不同
 */
public record TransferOrderRegisterRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String orderVersion,
        @NotBlank @Size(max = 64) String sourceCaseKey,
        @NotBlank @Size(max = 64) String targetCaseKey) {
}
