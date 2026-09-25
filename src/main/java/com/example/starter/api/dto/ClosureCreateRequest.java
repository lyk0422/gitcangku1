package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记跑道关闭窗口请求。窗口为 UTC 左闭右开 [startUtc, endUtc)；
 * 同一跑道窗口不得重叠，端点相接合法。closureKey 为幂等键，
 * 指纹含跑道版本、规范化时段、例外标志与操作者；同键重放返回首次结果，失败不占键。
 *
 * @param closureKey            关闭变更幂等键
 * @param runwayId              跑道标识
 * @param expectedRunwayVersion 期望的当前跑道版本（版本不匹配返回 409）
 * @param startUtc              关闭开始（含），UTC epoch 毫秒
 * @param endUtc                关闭结束（不含），UTC epoch 毫秒，必须大于 startUtc
 * @param allowEmergency        是否允许紧急例外
 * @param operator              登记操作者
 */
public record ClosureCreateRequest(
        @NotBlank @Size(max = 64) String closureKey,
        @NotBlank @Size(max = 64) String runwayId,
        @NotNull Integer expectedRunwayVersion,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        boolean allowEmergency,
        @NotBlank @Size(max = 64) String operator) {
}
