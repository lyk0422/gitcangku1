package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 批量离线观测提交响应：整批处理结果，数量字段返回实际值。
 *
 * @param requestId     批量提交的请求标识
 * @param totalCount    本批条目总数（实际值）
 * @param acceptedCount 围栏内自动合并接受的条目数（实际值）
 * @param rejectedCount 围栏外拒绝的条目数（实际值）
 * @param items         逐条处理结果，按提交顺序排列
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchSubmissionResponse(
        String requestId,
        int totalCount,
        int acceptedCount,
        int rejectedCount,
        List<ItemResult> items) {

    /**
     * 单条处理结果。
     *
     * @param submissionId        提交记录唯一标识
     * @param status              状态：ACCEPTED / GEOFENCE_REJECTED
     * @param matchResult         围栏匹配结果：INSIDE / OUTSIDE / NO_FENCE
     * @param matchedFenceId      匹配到的围栏标识（NO_FENCE 时不返回）
     * @param matchedFenceVersion 匹配到的围栏版本（NO_FENCE 时不返回）
     * @param mergedVersion       自动合并后的观测记录版本号（被拒时不返回）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemResult(
            String submissionId,
            String status,
            String matchResult,
            String matchedFenceId,
            Integer matchedFenceVersion,
            Integer mergedVersion) {

        /**
         * 由提交记录构造单条结果。
         */
        public static ItemResult of(SubmissionRecord record) {
            return new ItemResult(record.submissionId(), record.status(), record.matchResult(),
                    record.matchedFenceId(), record.matchedFenceVersion(), record.mergedVersion());
        }
    }
}
