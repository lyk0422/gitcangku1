package com.example.starter.observation.geofence;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 观测证据响应：字段、null 语义与落库证据一致；无生效围栏时围栏字段不返回。
 *
 * @param observationId       客户端观测唯一标识
 * @param siteId              采集站点标识
 * @param latitude            采集纬度
 * @param longitude           采集经度
 * @param observedAtUtc       采集时刻（UTC，ISO-8601）
 * @param payload             观测内容原文；未提供时不返回
 * @param matchResult         落库时的围栏匹配结果（不可变）
 * @param status              当前状态（人工裁决后可能与 matchResult 不同）
 * @param matchedFenceId      匹配到的围栏标识；无生效围栏时不返回
 * @param matchedFenceVersion 匹配到的围栏版本号；无生效围栏时不返回
 * @param operator            提交操作者标识
 * @param submittedAtUtc      服务端接收提交时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationEvidenceResponse(
        String observationId,
        String siteId,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant observedAtUtc,
        String payload,
        String matchResult,
        String status,
        String matchedFenceId,
        Integer matchedFenceVersion,
        String operator,
        Instant submittedAtUtc) {

    /**
     * 由证据记录构造响应。
     */
    public static ObservationEvidenceResponse of(ObservationEvidenceRecord record) {
        return new ObservationEvidenceResponse(record.observationId(), record.siteId(),
                record.latitude(), record.longitude(), record.observedAtUtc(), record.payload(),
                record.matchResult(), record.status(), record.matchedFenceId(),
                record.matchedFenceVersion(), record.operator(), record.submittedAtUtc());
    }
}
