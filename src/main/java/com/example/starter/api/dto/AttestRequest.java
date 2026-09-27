package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 见证提交请求：见证人确认的摘要串，必须与已登记摘要一致。
 */
public record AttestRequest(@NotBlank String digest) {
}
