package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建披露包请求。证物集合按字典序规范化；所有历史判定均基于 cutoffAt（UTC）。
 *
 * @param commandKey   幂等命令键
 * @param caseKey      案件键
 * @param evidenceKeys 证物业务键集合（非空，服务端去重并按字典序规范化）
 * @param cutoffAt     截至 UTC 时刻
 */
public record PackageCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotNull LocalDateTime cutoffAt) {
}
