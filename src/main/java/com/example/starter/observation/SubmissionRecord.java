package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线观测提交记录：对应 observation_submission 表的一行。
 * 原始坐标、采集/提交时刻与围栏匹配结果不可变；后续围栏改版不重算历史匹配结果。
 *
 * @param submissionId        提交唯一标识
 * @param requestId           所属批量提交请求的幂等标识
 * @param itemIndex           在批量请求中的序号，从 0 开始
 * @param operator            提交操作者标识
 * @param observationId       目标观测记录标识
 * @param siteId              采集站点标识
 * @param baseVersion         离线修改所基于的基线版本号
 * @param location            候选观测地点完整值
 * @param reading             候选观测读数完整值（十进制字符串）
 * @param note                候选观测备注完整值
 * @param latitude            采集纬度（度，WGS84），不可变
 * @param longitude           采集经度（度，WGS84），不可变
 * @param capturedAtUtc       采集时刻（UTC），不可变
 * @param submittedAtUtc      服务端接收提交时刻（UTC），不可变
 * @param matchStatus         围栏匹配结果：ACCEPTED / GEOFENCE_REJECTED，不可变
 * @param matchedFenceId      匹配到的围栏标识；未匹配为 null
 * @param matchedFenceVersion 匹配到的围栏配置版本号；未匹配为 null
 * @param observationVersion  自动合并后观测记录的版本号；被拒未合并为 null
 */
public record SubmissionRecord(
        String submissionId,
        String requestId,
        int itemIndex,
        String operator,
        String observationId,
        String siteId,
        int baseVersion,
        String location,
        String reading,
        String note,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant capturedAtUtc,
        Instant submittedAtUtc,
        String matchStatus,
        String matchedFenceId,
        Integer matchedFenceVersion,
        Integer observationVersion) {

    /**
     * 围栏匹配结果：落围栏内，已参与自动合并。
     */
    public static final String STATUS_ACCEPTED = "ACCEPTED";

    /**
     * 围栏匹配结果：落围栏外，保留原始证据但不参与自动合并。
     */
    public static final String STATUS_GEOFENCE_REJECTED = "GEOFENCE_REJECTED";
}
