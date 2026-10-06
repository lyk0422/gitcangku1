package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 显式身份归并请求：将两个合成访客编号所在的组合并为一组（无自动猜测身份）。
 * 编号对无方向，服务内部按升序归一化。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param visitorIdA      合成访客编号 A
 * @param visitorIdB      合成访客编号 B，须与 A 不同
 * @param operator        操作者
 * @param expectedVersion 期望版本：两编号分属不同组时为两组版本之和（未归并视为 0），
 *                        已在同一组时为该组当前版本；不匹配返回 409
 */
public record MergeIdentityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorIdA,
        @NotBlank @Size(max = 64) String visitorIdB,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(0) Integer expectedVersion
) {
}
