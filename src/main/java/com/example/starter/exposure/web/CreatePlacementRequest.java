package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增展示位请求。公告编号来自路径；创建后展示位不可修改、不可删除。
 *
 * @param requestId             写操作全局唯一幂等键
 * @param placementCode         展示位编号，公告内唯一，不得为 DEFAULT（默认展示位创建公告时已存在）
 * @param dailyCap              该展示位每 UTC 日额度，单位次，取值 1～100000 且不得超过公告日总额度
 * @param expectedConfigVersion 调用方所见的公告配置版本号；与当前版本不一致返回 409
 */
public record CreatePlacementRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String placementCode,
        @NotNull @Min(1) @Max(100_000) Integer dailyCap,
        @NotNull @Min(1) Integer expectedConfigVersion
) {
}
