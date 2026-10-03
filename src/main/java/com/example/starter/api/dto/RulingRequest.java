package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 复检裁决请求：质检员明确采纳原测或复检。 */
public record RulingRequest(
        @NotBlank @Pattern(regexp = "ORIGINAL|RECHECK") String decision,
        @NotBlank String inspector) {
}