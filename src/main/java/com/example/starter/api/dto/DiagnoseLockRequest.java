package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 违规诊断查询请求：按当前仓库状态对精确根版本做只读解析与策略评估。
 */
public record DiagnoseLockRequest(
        @NotBlank String rootName,
        @Positive int rootVersion) {
}
