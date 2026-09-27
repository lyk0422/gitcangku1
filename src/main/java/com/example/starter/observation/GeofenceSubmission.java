package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线观测地理围栏提交：对应 geofence_submission 表的一行。
 * 原始坐标、采集时刻、匹配结果、匹配围栏与提交时刻不可变；
 * 仅 status / decidedBy / decidedAtUtc / decisionReason / mergeVersion 允许被人工接受流程推进一次。
 *
 * @param submissionId      全局唯一提交标识
 * @param requestId         生成该提交的批量请求标识
 * @param batchSeq          批内提交顺序号，从 0 开始
 * @param siteId            站点标识
 * @param observationId     目标观测记录标识
 * @param baseVersion       离线修改所基于的基线版本号
 * @param candidateLocation 候选观测地点完整值
 * @param candidateReading  候选观测读数完整值（十进制原文）
 * @param candidateNote     候选观测备注完整值
 * @param latitude          采集纬度（原始证据，不可变）
 * @param longitude         采集经度（原始证据，不可变）
 * @param observedAtUtc     采集时刻（UTC），围栏按此刻匹配
 * @param matchResult       围栏匹配结果（不可变）：ACCEPTED / GEOFENCE_REJECTED
 * @param matchedFenceId    匹配到的围栏标识；无生效围栏时为 null
 * @param status            当前状态：ACCEPTED / GEOFENCE_REJECTED / MANUALLY_ACCEPTED
 * @param mergeVersion      合并后指向的观测版本号；未合并（被拒）时为 null
 * @param operator          提交操作者标识
 * @param submittedAtUtc    服务端受理提交时刻（UTC）
 * @param decidedBy         人工接受操作者标识；未人工接受时为 null
 * @param decidedAtUtc      人工接受时刻（UTC）；未人工接受时为 null
 * @param decisionReason    人工接受理由；未人工接受时为 null
 */
public record GeofenceSubmission(
        String submissionId,
        String requestId,
        int batchSeq,
        String siteId,
        String observationId,
        int baseVersion,
        String candidateLocation,
        String candidateReading,
        String candidateNote,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant observedAtUtc,
        String matchResult,
        String matchedFenceId,
        String status,
        Integer mergeVersion,
        String operator,
        Instant submittedAtUtc,
        String decidedBy,
        Instant decidedAtUtc,
        String decisionReason) {

    /**
     * 匹配结果/状态：落在生效围栏内或站点无生效围栏，已参与自动合并。
     */
    public static final String ACCEPTED = "ACCEPTED";

    /**
     * 匹配结果/状态：落在生效围栏外，仅保留原始证据，不参与自动合并。
     */
    public static final String GEOFENCE_REJECTED = "GEOFENCE_REJECTED";

    /**
     * 状态：原被拒提交经人工接受（必须填写理由）并已合并。
     */
    public static final String MANUALLY_ACCEPTED = "MANUALLY_ACCEPTED";
}
