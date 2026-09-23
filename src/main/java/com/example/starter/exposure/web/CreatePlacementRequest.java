package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增展示位请求。展示位创建后不可修改、不可删除；每公告最多 20 个（含 DEFAULT）。
 *
 * @param requestId             写操作全局唯一幂等键
 * @param placementCode         新展示位编号，公告内唯一，1～64 字符；不得使用保留字 DEFAULT
 * @param dailyCap              该展示位每 UTC 日额度，单位次，取值 1～100000 且不得超过公告日总额度
 * @param expectedConfigVersion 客户端期望的当前配置版本号；与服务端不一致返回 409
 */
public record CreatePlacementRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(min = 1, max = 64)
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "placementCode 仅允许字母、数字、下划线与连字符")
        String placementCode,
        @NotNull @Min(1) @Max(100_000) Integer dailyCap,
        @NotNull @Min(1) Integer expectedConfigVersion
) {
}
