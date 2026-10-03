package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 整批放行请求：requestId 幂等，同参重放、异参 409。 */
public record ReleaseRequest(@NotBlank String requestId) {
}