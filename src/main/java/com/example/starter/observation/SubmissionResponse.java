package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 离线观测提交响应：返回提交证据的完整快照；null 字段省略（NO_FENCE 时无匹配围栏，
 * 被拒时无合并版本），字段、排序、时间精度与 null 语义保持稳定。
 *
 * @param submissionId        提交记录唯一标识
 * @param siteId              站点标识
 * @param observationId       目标观测记录标识
 * @param baseVersion         离线修改所基于的基线版本号
 * @param location            观测地点候选完整值
 * @param reading             观测读数候选完整值
 * @param note                观测备注候选完整值
 * @param latitude            采集纬度
 * @param longitude           采集经度
 * @param collectedAtUtc      采集时刻（UTC，ISO-8601）
 * @param submittedAtUtc      服务端接收提交时刻（UTC，ISO-8601）
 * @param status              状态：ACCEPTED / GEOFENCE_REJECTED / MANUAL_ACCEPTED
 * @param matchResult         围栏匹配结果：INSIDE / OUTSIDE / NO_FENCE
 * @param matchedFenceId      匹配到的围栏标识（NO_FENCE 时不返回）
 * @param matchedFenceVersion 匹配到的围栏版本（NO_FENCE 时不返回）
 * @param mergedVersion       合并后的观测记录版本号（被拒未合并时不返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionResponse(
        String submissionId,
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

    /**
     * 由提交记录构造响应。
     */
    public static SubmissionResponse of(SubmissionRecord record) {
        return new SubmissionResponse(record.submissionId(), record.siteId(), record.observationId(),
                record.baseVersion(), record.location(), record.reading(), record.note(),
                record.latitude(), record.longitude(), record.collectedAtUtc(), record.submittedAtUtc(),
                record.status(), record.matchResult(), record.matchedFenceId(),
                record.matchedFenceVersion(), record.mergedVersion());
    }
}
