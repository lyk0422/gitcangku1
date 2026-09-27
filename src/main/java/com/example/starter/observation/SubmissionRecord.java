package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线观测提交记录：对应 observation_submission 表的一行。
 * 原始坐标、采集时刻、提交时刻与匹配结果不可变；仅状态与合并版本可经人工裁决推进。
 *
 * @param submissionId        提交记录唯一标识
 * @param batchRequestId      所属批量提交的请求标识
 * @param batchSeq            批内顺序号，从 1 开始
 * @param siteId              站点标识
 * @param observationId       目标观测记录标识
 * @param baseVersion         离线修改所基于的基线版本号
 * @param location            观测地点候选完整值
 * @param reading             观测读数候选完整值（十进制原文）
 * @param note                观测备注候选完整值
 * @param latitude            采集纬度
 * @param longitude           采集经度
 * @param collectedAtUtc      采集时刻（UTC）
 * @param submittedAtUtc      服务端接收提交时刻（UTC）
 * @param status              状态：ACCEPTED / GEOFENCE_REJECTED / MANUAL_ACCEPTED
 * @param matchResult         围栏匹配结果：INSIDE / OUTSIDE / NO_FENCE
 * @param matchedFenceId      匹配到的围栏标识；NO_FENCE 时为 null
 * @param matchedFenceVersion 匹配到的围栏版本；NO_FENCE 时为 null
 * @param mergedVersion       自动或人工合并后的观测记录版本号；未合并（被拒）时为 null
 */
public record SubmissionRecord(
        String submissionId,
        String batchRequestId,
        int batchSeq,
        String siteId,
        String observationId,
        int baseVersion,
        String location,
        String reading,
        String note,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant collectedAtUtc,
        Instant submittedAtUtc,
        String status,
        String matchResult,
        String matchedFenceId,
        Integer matchedFenceVersion,
        Integer mergedVersion) {

    /** 状态：围栏内自动合并接受。 */
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    /** 状态：围栏外拒绝，保留原始证据，不参与自动合并。 */
    public static final String STATUS_GEOFENCE_REJECTED = "GEOFENCE_REJECTED";
    /** 状态：人工裁决接受。 */
    public static final String STATUS_MANUAL_ACCEPTED = "MANUAL_ACCEPTED";

    /** 匹配结果：落在围栏矩形内。 */
    public static final String MATCH_INSIDE = "INSIDE";
    /** 匹配结果：落在围栏矩形外。 */
    public static final String MATCH_OUTSIDE = "OUTSIDE";
    /** 匹配结果：采集时刻无生效围栏。 */
    public static final String MATCH_NO_FENCE = "NO_FENCE";
}
