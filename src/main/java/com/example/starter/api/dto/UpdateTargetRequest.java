package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/** 修改目标值请求：成功后目标版本递增，旧测色记录保留但其版本变旧。 */
public record UpdateTargetRequest(
        @NotNull Double targetL,
        @NotNull Double targetA,
        @NotNull Double targetB) {
}