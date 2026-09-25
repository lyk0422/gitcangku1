package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 制品版本镜像源登记请求：mirrorId 为镜像源标识，priority 为优先级（数字越小优先级越高，1 最高）。
 */
public record RegisterMirrorRequest(
        @NotBlank @Size(max = 128) String mirrorId,
        @Positive int priority) {
}
