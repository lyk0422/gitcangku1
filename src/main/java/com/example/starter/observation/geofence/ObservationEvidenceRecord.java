package com.example.starter.observation.geofence;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 观测证据记录：对应 geofence_observation 表的一行。
 * 除 status 可经人工裁决改变外，其余字段落库后不可变。
 *
 * @param observationId       客户端观测唯一标识
 * @param requestId           提交该观测的批量请求标识
 * @param batchSeq            观测在批量请求中的序号，从 0 开始
 * @param siteId              采集站点标识
 * @param latitude            采集纬度
 * @param longitude           采集经度
 * @param observedAtUtc       采集时刻（UTC）
 * @param payload             观测内容原文；为 null 表示客户端未提供
 * @param matchResult         落库时的围栏匹配结果，不可变
 * @param status              当前状态
 * @param matchedFenceId      匹配到的围栏标识；无生效围栏时为 null
 * @param matchedFenceVersion 匹配到的围栏版本号；无生效围栏时为 null
 * @param operator            提交操作者标识
 * @param submittedAtUtc      服务端接收提交时刻（UTC），不可变
 */
public record ObservationEvidenceRecord(
        String observationId,
        String requestId,
        int batchSeq,
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
}
