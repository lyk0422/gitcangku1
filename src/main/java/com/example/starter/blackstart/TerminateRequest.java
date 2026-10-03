package com.example.starter.blackstart;

import jakarta.validation.constraints.NotBlank;

/**
 * 值班员终止计划请求，reason 记录为终止原因。
 */
public record TerminateRequest(@NotBlank String reason) {
}