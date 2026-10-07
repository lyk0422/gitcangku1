package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 组合申请曝光请求：一个合成访客一次申请 2～5 个不同公告，整组同生共死。
 * 幂等身份由 requestId 全局唯一键承载，指纹含非空操作者、操作、资源、
 * 全参数（公告集合按序归一，换序同参）及期望版本。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param operator        操作者标识，非空
 * @param visitorId       合成访客编号
 * @param campaignIds     公告编号集合，2～5 个且互不重复；集合换序视为同参
 * @param expectedVersion 期望版本，新建组合语义下由调用方显式给出（按值比较）
 */
public record ApplyBundleRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull @Size(min = 2, max = 5) List<@NotBlank @Size(max = 64) String> campaignIds,
        @NotNull @Min(0) Long expectedVersion
) {
}
