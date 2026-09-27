package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单条预测气象（某区域某 UTC 时段，左闭右开）。
 *
 * @param zoneId      预测覆盖的区域标识（须已存在）
 * @param startMinute 时段起始（含），范围 [0,1440)
 * @param endMinute   时段结束（不含），范围 (0,1440]，须大于 startMinute
 * @param visibilityM 预测能见度，单位米，必须 >= 0（负值返回 422）
 * @param cloudBaseM  预测云底高，单位米，必须 >= 0（负值返回 422）
 */
public record ForecastEntryDto(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull Integer startMinute,
        @NotNull Integer endMinute,
        @NotNull Integer visibilityM,
        @NotNull Integer cloudBaseM) {
}
