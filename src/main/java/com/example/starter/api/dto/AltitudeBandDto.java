package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 高度带定义。左闭右开区间 [lowerM, upperM)，单位米；同一区域内不得重叠（端点相接合法）。
 *
 * @param bandId   高度带标识，区域内唯一
 * @param lowerM   高度下限（含），单位米
 * @param upperM   高度上限（不含），单位米，lowerM < upperM
 * @param capacity 同时容量，1~50
 */
public record AltitudeBandDto(
        @NotBlank @Size(max = 64) String bandId,
        @NotNull @Min(0) @Max(100000) Integer lowerM,
        @NotNull @Min(0) @Max(100000) Integer upperM,
        @NotNull @Min(1) @Max(50) Integer capacity) {
}
