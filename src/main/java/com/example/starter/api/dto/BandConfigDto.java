package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 单个高度带配置项。{@code [lowerAltitude, upperAltitude)} 左闭右开，单位米。
 *
 * @param bandId         高度带标识；新增带时由客户端提供（区域内唯一），
 *                       对已存在带提供相同 bandId 表示按上下限匹配后仅上调容量
 * @param lowerAltitude  高度带下限（含，米）
 * @param upperAltitude  高度带上限（不含，米），须大于下限
 * @param capacity       同时段容量，范围 1~50
 */
public record BandConfigDto(
        @NotNull String bandId,
        @NotNull Integer lowerAltitude,
        @NotNull Integer upperAltitude,
        @NotNull @Min(1) @Max(50) Integer capacity) {
}
