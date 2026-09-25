package com.example.starter.firmware.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 区域上限配置修改请求：expectedVersion 必须等于发布单当前版本，成功后版本加一；
 * limits 为全量替换，空列表表示清除全部区域上限。只影响后续拉取判定，不影响已下发任务。
 */
public record UpdateRegionLimitsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @NotNull List<@Valid RegionLimitItem> limits) {
}
