package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 组合申请曝光请求：一个合成访客一次申请 2～5 个不同公告，
 * 整组共用服务端 60 秒截止与申请 UTC 日；集合换序视为同参。
 *
 * @param requestId   写操作全局唯一幂等键
 * @param visitorId   合成访客编号（操作者，非空）
 * @param campaignIds 公告编号集合，2～5 个且互不重复
 */
public record ApplyBundleRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull @Size(min = 2, max = 5) List<@NotBlank @Size(max = 64) String> campaignIds
) {
}
