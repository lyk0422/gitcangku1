package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线观测提交明细响应：包含不可变原始证据（坐标、采集时刻、匹配结果、提交时刻）与当前状态。
 * null 语义稳定：matchedFenceId 仅无生效围栏时为 null；mergeVersion 仅未合并时为 null；
 * decidedBy/decidedAtUtc/decisionReason 仅未人工接受时为 null。
 *
 * @param submissionId      全局唯一提交标识
 * @param requestId         生成该提交的批量请求标识
 * @param batchSeq          批内提交顺序号
 * @param siteId            站点标识
 * @param observationId     目标观测记录标识
 * @param baseVersion       基线版本号
 * @param candidateLocation 候选观测地点完整值
 * @param candidateReading  候选观测读数完整值
 * @param candidateNote     候选观测备注完整值
 * @param latitude          采集纬度（原始证据）
 * @param longitude         采集经度（原始证据）
 * @param observedAtUtc     采集时刻（UTC）
 * @param matchResult       围栏匹配结果（不可变）：ACCEPTED / GEOFENCE_REJECTED
 * @param matchedFenceId    匹配到的围栏标识；无生效围栏时为 null
 * @param status            当前状态：ACCEPTED / GEOFENCE_REJECTED / MANUALLY_ACCEPTED
 * @param mergeVersion      合并后指向的观测版本号；未合并时为 null
 * @param operator          提交操作者标识
 * @param submittedAtUtc    服务端受理提交时刻（UTC）
 * @param decidedBy         人工接受操作者标识；未人工接受时为 null
 * @param decidedAtUtc      人工接受时刻（UTC）；未人工接受时为 null
 * @param decisionReason    人工接受理由；未人工接受时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeofenceSubmissionResponse(
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
     * 由提交记录构造响应。
     */
    public static GeofenceSubmissionResponse of(GeofenceSubmission submission) {
        return new GeofenceSubmissionResponse(submission.submissionId(), submission.requestId(),
                submission.batchSeq(), submission.siteId(), submission.observationId(),
                submission.baseVersion(), submission.candidateLocation(), submission.candidateReading(),
                submission.candidateNote(), submission.latitude(), submission.longitude(),
                submission.observedAtUtc(), submission.matchResult(), submission.matchedFenceId(),
                submission.status(), submission.mergeVersion(), submission.operator(),
                submission.submittedAtUtc(), submission.decidedBy(), submission.decidedAtUtc(),
                submission.decisionReason());
    }
}
