package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 密封批次压力曲线提交请求。
 *
 * <p>节点按提交顺序给出，2～10 个；每个节点为相对秒数与非负压力（千帕，最多 3 位小数）。
 * 节点顺序属于身份信息，服务端不排序：首点秒数必须为 0，相邻秒数严格递增，末点至少 10 秒。
 * 期望版本 expectedVersion 须与批次当前版本一致，否则 409；操作人经 X-Actor-Id 头提供，
 * 全局请求标识经 X-Request-Id 头提供。
 */
public record SubmitPressureCurveRequest(
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion,
        @NotNull(message = "points 不能为空")
        @jakarta.validation.constraints.Size(min = 2, max = 10, message = "points 必须包含 2～10 个节点")
        List<@Valid @NotNull(message = "节点不能为空") PressurePoint> points
) {

    /**
     * 曲线节点：second 为按秒递增的相对时刻，pressureKpa 为压力文本（千帕，非负，最多 3 位小数）。
     * 十进制按值比较、文本原样保留，例如 9.5 与 9.50 数值相等但文本身份不同。
     */
    public record PressurePoint(
            @NotNull(message = "second 不能为空") Long second,
            @NotBlank(message = "pressureKpa 不能为空") String pressureKpa
    ) {
    }
}
