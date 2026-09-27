package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 冲突簇证据查询响应：原始观测（含维护标记）、不可变合并快照与人工裁决历史。
 * 所有内容按落库原样返回，读取不改变任何状态。
 *
 * @param clusterId           冲突簇标识
 * @param status              当前状态：OPEN / MERGED / PENDING_MANUAL
 * @param winnerSubmissionId  当前有效胜出提交；未定案时不返回
 * @param observations        簇内全部原始观测（含提交时刻固化的维护标记）
 * @param snapshot            合并快照（未合并时不返回）
 * @param adjudications       人工裁决历史（按裁决时刻先后排序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterEvidenceResponse(
        String clusterId,
        String status,
        String winnerSubmissionId,
        List<StationObservationResponse> observations,
        MergeResultResponse snapshot,
        List<AdjudicationResponse> adjudications) {
}
