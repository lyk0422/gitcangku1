package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 气象最低标准时段（UTC 一日内分钟，左闭右开 [startMinute, endMinute)）。
 *
 * @param startMinute    时段起始（含），范围 [0,1440)
 * @param endMinute      时段结束（不含），范围 (0,1440]，须大于 startMinute
 * @param minVisibilityM 该时段最低能见度标准，单位米，必须 >= 0（负值返回 422）
 * @param minCloudBaseM  该时段最低云底高标准，单位米，必须 >= 0（负值返回 422）
 */
public record WeatherWindowDto(
        @NotNull Integer startMinute,
        @NotNull Integer endMinute,
        @NotNull Integer minVisibilityM,
        @NotNull Integer minCloudBaseM) {
}
