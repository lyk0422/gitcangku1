package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记跑道关闭窗口请求。窗口为 UTC 左闭右开 [startUtc, endUtc)。
 * 幂等键 closureKey 由服务端按指纹（跑道标识与版本、规范化时段、例外标志、操作者）计算：
 * 同键重放原结果，失败不占键。
 *
 * @param runwayId              跑道唯一标识
 * @param expectedRunwayVersion 携带的跑道版本，须等于当前版本否则 409
 * @param startUtc              关闭开始时刻，epoch 毫秒（UTC），左闭
 * @param endUtc                关闭结束时刻，epoch 毫秒（UTC），右开，必须大于 startUtc
 * @param allowEmergency        是否允许紧急例外
 * @param operator              登记操作者标识
 */
public record ClosureCreateRequest(
        @NotBlank @Size(max = 64) String runwayId,
        @NotNull Integer expectedRunwayVersion,
        @NotNull Long startUtc,
        @NotNull Long endUtc,
        boolean allowEmergency,
        @NotBlank @Size(max = 64) String operator) {
}
