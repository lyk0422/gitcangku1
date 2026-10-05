package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 禁运明细/诊断视图：禁运设置要点及发布状态。
 *
 * @param observationId   观测记录唯一标识（簇）
 * @param embargoUntilUtc 禁运截止时刻（UTC，ISO-8601）
 * @param disclosureScope 披露范围标签（已规范化）
 * @param operator        设置禁运的操作者标识
 * @param requestId       设置禁运的请求标识
 * @param versionAtSet    设置时簇的当前版本号
 * @param setAtUtc        设置完成时刻（UTC，ISO-8601）
 * @param status          状态：PENDING（未发布）/ RELEASED（已发布）
 * @param releaseId       发布快照标识（仅 RELEASED 时非空）
 * @param releasedAtUtc   首次发布时刻（UTC，仅 RELEASED 时非空）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmbargoDetailResponse(
        String observationId,
        Instant embargoUntilUtc,
        List<String> disclosureScope,
        String operator,
        String requestId,
        int versionAtSet,
        Instant setAtUtc,
        String status,
        String releaseId,
        Instant releasedAtUtc) {
}
