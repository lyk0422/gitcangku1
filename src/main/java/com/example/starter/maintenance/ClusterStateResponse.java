package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 冲突簇当前状态响应：综合合并快照与最新人工裁决得出有效状态。
 *
 * @param clusterId           冲突簇标识
 * @param status              当前状态：OPEN 未合并 / MERGED 已定案 / PENDING_MANUAL 待人工裁决
 * @param winnerSubmissionId  当前有效胜出提交（自动合并或最近一次人工裁决）；未定案时不返回
 * @param observationCount    簇内观测提交总数（实际值）
 * @param adjudicationCount   人工裁决记录数（实际值）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterStateResponse(
        String clusterId,
        String status,
        String winnerSubmissionId,
        int observationCount,
        int adjudicationCount) {

    /** 簇状态：已有观测提交但尚未合并。 */
    public static final String STATUS_OPEN = "OPEN";
}
