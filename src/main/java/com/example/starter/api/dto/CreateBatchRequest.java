package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 创建染色批次请求：批次号与目标 Lab 值（目标版本自 1 起）。 */
public record CreateBatchRequest(
        @NotBlank String batchNo,
        @NotNull Double targetL,
        @NotNull Double targetA,
        @NotNull Double targetB) {
}