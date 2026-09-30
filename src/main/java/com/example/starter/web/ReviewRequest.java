package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 人工复核请求；approved=true 复核通过并重新确认靠泊，false 拒绝。
 */
public record ReviewRequest(
        @NotBlank String requestId,
        @NotNull Boolean approved) {
}
