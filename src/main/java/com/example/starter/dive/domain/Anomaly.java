package com.example.starter.dive.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一条审核异常，携带类型、说明与涉及的点位序号/时刻/深度，便于定位异常区间。
 *
 * @param type        异常类型
 * @param message     具体说明（含阈值与实际值）
 * @param fromSeq     涉及区间的起始点位序号，单点异常时与toSeq相同，全局异常为空
 * @param toSeq       涉及区间的结束点位序号，单点异常时与fromSeq相同，全局异常为空
 * @param fromTimeUtc 区间起始时刻（UTC），全局异常为空
 * @param toTimeUtc   区间结束时刻（UTC），全局异常为空
 * @param depthM      触发异常的实际深度（米），非深度类异常为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Anomaly(
        AnomalyType type,
        String message,
        Integer fromSeq,
        Integer toSeq,
        Instant fromTimeUtc,
        Instant toTimeUtc,
        BigDecimal depthM) {

    /** 构造单点异常。 */
    public static Anomaly atPoint(AnomalyType type, String message, DepthPoint point, BigDecimal depthM) {
        return new Anomaly(type, message, point.seq(), point.seq(),
                point.recordedAtUtc(), point.recordedAtUtc(), depthM);
    }

    /** 构造相邻两点之间的区间异常。 */
    public static Anomaly between(AnomalyType type, String message, DepthPoint from, DepthPoint to) {
        return new Anomaly(type, message, from.seq(), to.seq(),
                from.recordedAtUtc(), to.recordedAtUtc(), null);
    }

    /** 构造不针对具体点位的全局异常。 */
    public static Anomaly global(AnomalyType type, String message) {
        return new Anomaly(type, message, null, null, null, null, null);
    }
}