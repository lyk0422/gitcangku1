package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇合并结果响应：状态、胜出提交与数量均为合并时刻的实际值；快照不可变。
 *
 * @param clusterId           冲突簇标识
 * @param status              合并结果：MERGED / PENDING_MANUAL
 * @param winnerSubmissionId  胜出提交标识；待人工裁决时不返回
 * @param totalCount          簇内观测提交总数（实际值）
 * @param affectedCount       维护受影响提交数（实际值）
 * @param eligibleCount       可参与自动胜出的未受影响提交数（实际值）
 * @param marks               各提交维护标记快照（按提交受理时刻、提交标识稳定排序）
 * @param mergedAtUtc         合并完成时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergeResultResponse(
        String clusterId,
        String status,
        String winnerSubmissionId,
        int totalCount,
        int affectedCount,
        int eligibleCount,
        List<MergeMarkEntry> marks,
        Instant mergedAtUtc) {
}
