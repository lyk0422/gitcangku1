package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增展示位请求。展示位创建后不可修改或删除。
 *
 * @param requestId             写操作全局唯一幂等键
 * @param placementCode         展示位编号；公告内唯一，不能与已有编号（含 DEFAULT）重复
 * @param dailyCap              该展示位每 UTC 日额度，单位次，取值 1～100000 且不超过公告日总额度
 * @param expectedConfigVersion 调用方基于的公告配置版本号；与当前版本不一致时返回 409
 */
public record CreatePlacementRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String placementCode,
        @NotNull @Min(1) @Max(100_000) Integer dailyCap,
        @NotNull @Min(1) Integer expectedConfigVersion
) {
}
