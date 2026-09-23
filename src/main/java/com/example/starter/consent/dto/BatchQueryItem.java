package com.example.starter.consent.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量查询单项：指定主体在指定用途下的期望授权代次与记录键。
 *
 * @param subjectKey    主体标识（合成字符串）
 * @param expectedEpoch 期望命中的授权代次，必须为该主体该用途当前 ACTIVE 代次
 * @param recordKey     记录键（合成字符串）
 */
public record BatchQueryItem(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull @Min(1) Integer expectedEpoch,
        @NotBlank @Size(max = 128) String recordKey) {
}
