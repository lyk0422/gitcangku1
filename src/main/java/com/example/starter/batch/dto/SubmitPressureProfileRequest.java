package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交压力曲线检验请求。requestId 为全局幂等键：同参重放返回首次完整响应，异参 409，
 * 失败不占键；operator 为非空操作者（同时作为检验人记录）。expectedVersion 可选，
 * 提供时须等于批次当前版本号，否则 409。points 为 2～10 个测量点，首点秒数 0、
 * 末点至少 10、按秒严格递增；压力单位千帕，非负，最多 3 位小数。
 */
public record SubmitPressureProfileRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        Long expectedVersion,
        @NotNull(message = "points 不能为空")
        @Size(min = 2, max = 10, message = "points 必须包含 2～10 个测量点")
        List<@Valid CurvePoint> points
) {
    /**
     * 曲线测量点：offsetSeconds 为相对时刻（秒，整数），pressureKpa 为压力（千帕）。
     */
    public record CurvePoint(
            @NotNull(message = "offsetSeconds 不能为空") Long offsetSeconds,
            @NotNull(message = "pressureKpa 不能为空") BigDecimal pressureKpa
    ) {
    }
}
