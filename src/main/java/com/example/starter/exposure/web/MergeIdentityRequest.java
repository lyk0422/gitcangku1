package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 显式身份归并请求：将两个合成访客（及其各自所在组）并为一组。
 * 不做任何自动身份猜测，仅按显式请求归并。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param operator        操作者标识，纳入幂等身份
 * @param visitorIdA      待归并编号 A；若已在组内，其所在组为存续组
 * @param visitorIdB      待归并编号 B；须与 A 不同
 * @param expectedVersion 期望的存续组当前版本号；两边均未入组时须为 0，版本不符返回 409
 */
public record MergeIdentityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String visitorIdA,
        @NotBlank @Size(max = 64) String visitorIdB,
        @NotNull @Min(0) Integer expectedVersion
) {
}
