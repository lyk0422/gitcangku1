package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 观测记录诊断响应：禁运状态、发布状态与未解决冲突的只读汇总。
 *
 * @param observationId     观测记录唯一标识
 * @param embargoed         当前是否处于禁运期内（禁运已设置、未发布且当前时刻早于截止时刻）
 * @param embargoSet        是否已设置禁运（无论是否到期）
 * @param embargoUntilUtc   禁运截止时刻（UTC，ISO-8601）；未设置禁运时为 null（响应中省略）
 * @param scope             披露范围标签集合；未设置禁运时为 null（响应中省略）
 * @param released          是否已生成首次披露快照
 * @param releasedAtUtc     首次发布时刻（UTC，ISO-8601）；未发布时为 null（响应中省略）
 * @param openConflictCount 未解决冲突数量（实际值，等于 openConflicts 大小）
 * @param openConflicts     未解决冲突明细，按检出时刻升序稳定排序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosticsResponse(
        String observationId,
        boolean embargoed,
        boolean embargoSet,
        Instant embargoUntilUtc,
        List<String> scope,
        boolean released,
        Instant releasedAtUtc,
        int openConflictCount,
        List<OpenConflictInfo> openConflicts) {

    /**
     * 单条未解决冲突诊断明细。
     *
     * @param conflictFields 未解决冲突字段名列表
     * @param baseVersion    冲突判定时的基线版本号
     * @param currentVersion 冲突判定时的当前版本号
     * @param requestId      触发冲突判定的合并请求标识
     * @param detectedAtUtc  最近一次冲突检出时刻（UTC，ISO-8601）
     */
    public record OpenConflictInfo(
            List<String> conflictFields,
            int baseVersion,
            int currentVersion,
            String requestId,
            Instant detectedAtUtc) {
    }
}
