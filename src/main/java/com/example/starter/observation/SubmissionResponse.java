package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线观测提交响应：批量结果项与明细查询共用的稳定结构。
 * 原始坐标、采集/提交时刻与匹配结果不可变；被拒提交的合并版本号为 null。
 *
 * @param submissionId        提交唯一标识
 * @param observationId       目标观测记录标识
 * @param siteId              采集站点标识
 * @param matchStatus         围栏匹配结果：ACCEPTED / GEOFENCE_REJECTED
 * @param matchedFenceId      匹配到的围栏标识；未匹配为 null
 * @param matchedFenceVersion 匹配到的围栏配置版本号；未匹配为 null
 * @param observationVersion  合并后观测记录的版本号；被拒未合并为 null
 * @param latitude            采集纬度（度，WGS84）
 * @param longitude           采集经度（度，WGS84）
 * @param capturedAtUtc       采集时刻（UTC）
 * @param submittedAtUtc      服务端接收提交时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionResponse(
        String submissionId,
        String observationId,
        String siteId,
        String matchStatus,
        String matchedFenceId,
        Integer matchedFenceVersion,
        Integer observationVersion,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant capturedAtUtc,
        Instant submittedAtUtc) {

    /**
     * 由提交记录构造响应。
     */
    public static SubmissionResponse of(SubmissionRecord record) {
        return new SubmissionResponse(record.submissionId(), record.observationId(), record.siteId(),
                record.matchStatus(), record.matchedFenceId(), record.matchedFenceVersion(),
                record.observationVersion(), record.latitude(), record.longitude(),
                record.capturedAtUtc(), record.submittedAtUtc());
    }
}
