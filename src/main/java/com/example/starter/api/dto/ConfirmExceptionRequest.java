package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 豁免确认请求：第二审核人确认，必须与第一审核人不同。
 */
public record ConfirmExceptionRequest(@NotBlank String reviewer) {
}
