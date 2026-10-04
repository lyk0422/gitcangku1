package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 互斥能力对登记/撤销请求：两个能力名无序，相同能力不构成互斥。
 */
public record CapabilityExclusionRequest(
        @NotBlank String capabilityA,
        @NotBlank String capabilityB) {
}