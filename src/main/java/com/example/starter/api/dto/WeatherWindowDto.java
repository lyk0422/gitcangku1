package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 区域分时段气象最低标准窗口。时段为 UTC 左闭右开区间 [startUtc, endUtc)。
 * 负值标准在业务校验时返回 422（此处只做非空校验）。
 *
 * @param startUtc       时段起点（含），epoch 毫秒（UTC）
 * @param endUtc         时段终点（不含），epoch 毫秒（UTC），必须大于起点
 * @param minVisibilityM 最低能见度标准（米），必须 >= 0，否则 422
 * @param minCloudBaseM  最低云底高度标准（米），必须 >= 0，否则 422
 */
public record WeatherWindowDto(
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        @NotNull Integer minVisibilityM,
        @NotNull Integer minCloudBaseM) {
}