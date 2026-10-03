package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登轮点变更请求。
 */
public record BoardingPointChangeRequest(@NotBlank String boardingPoint) {
}