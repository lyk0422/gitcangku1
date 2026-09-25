package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 区域上限修改请求：expectedVersion 必须等于发布单当前版本，成功加一；
 * regionLimit 取值1~1000，为 null 表示清除限流。只影响后续拉取判定，不影响已下发任务。
 */
public record UpdateRegionLimitRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @Min(1) @Max(1000) Integer regionLimit) {
}
