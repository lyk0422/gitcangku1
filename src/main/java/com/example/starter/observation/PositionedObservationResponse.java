package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 观测点响应：普通版本携带坐标与协方差矩阵完整字段组；删除墓碑版本只返回删除状态和版本。
 * 十进制值以字符串原文返回，单位：坐标米、矩阵元素平方米。
 *
 * @param observationId 观测点唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param x             X 坐标（墓碑版本不返回）
 * @param y             Y 坐标（墓碑版本不返回）
 * @param a             协方差矩阵元素 a（墓碑版本不返回）
 * @param b             协方差矩阵元素 b（墓碑版本不返回）
 * @param c             协方差矩阵元素 c（墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionedObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String x,
        String y,
        String a,
        String b,
        String c) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段。
     */
    public static PositionedObservationResponse of(PositionedObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new PositionedObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null);
        }
        return new PositionedObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.x().toPlainString(), snapshot.y().toPlainString(),
                snapshot.a().toPlainString(), snapshot.b().toPlainString(), snapshot.c().toPlainString());
    }
}
