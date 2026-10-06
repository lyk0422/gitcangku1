package com.example.starter.observation.geo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 二维坐标观测记录响应：普通版本携带坐标与协方差矩阵全部字段；
 * 删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId 观测记录唯一标识
 * @param version       版本号
 * @param deleted       是否为删除墓碑
 * @param x             观测点 X 坐标（米，十进制原文；墓碑版本不返回）
 * @param y             观测点 Y 坐标（米，十进制原文；墓碑版本不返回）
 * @param a             协方差矩阵元素 a（平方米；墓碑版本不返回）
 * @param b             协方差矩阵元素 b（平方米；墓碑版本不返回）
 * @param c             协方差矩阵元素 c（平方米；墓碑版本不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeoObservationResponse(
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
    public static GeoObservationResponse of(GeoObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new GeoObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null);
        }
        return new GeoObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.x(), snapshot.y(), snapshot.a(), snapshot.b(), snapshot.c());
    }
}
