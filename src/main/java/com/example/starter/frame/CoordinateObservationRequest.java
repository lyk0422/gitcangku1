package com.example.starter.frame;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 接收坐标观测请求：观测携带原基准、原坐标与观测时刻（UTC）。
 *
 * <p>服务端按观测时刻选择左闭右开区间内唯一生效的规则完成基准转换，
 * 再按站点矩形判定自动合并或待复核；缺少规则或命中多条规则返回 422 且不入库。
 *
 * @param requestId               全局唯一请求标识（幂等去重键）；重放首次坐标与状态，改原基准或坐标返回 409
 * @param coordinateObservationId 坐标观测业务标识
 * @param sourceDatum             原基准名称
 * @param sourceX                 原 X 坐标，十进制字符串（最多九位小数）
 * @param sourceY                 原 Y 坐标，十进制字符串（最多九位小数）
 * @param observedAtUtc           观测时刻（UTC），ISO-8601 形式，规则选择依据
 */
public record CoordinateObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String coordinateObservationId,
        @NotBlank @Size(max = 64) String sourceDatum,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,9})?", message = "sourceX must be a decimal string with at most 9 fraction digits")
        String sourceX,
        @NotNull @Pattern(regexp = "-?\\d+(\\.\\d{1,9})?", message = "sourceY must be a decimal string with at most 9 fraction digits")
        String sourceY,
        @NotNull @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z",
                message = "observedAtUtc must be an ISO-8601 UTC instant, e.g. 2026-09-10T08:00:00Z")
        String observedAtUtc) {
}
