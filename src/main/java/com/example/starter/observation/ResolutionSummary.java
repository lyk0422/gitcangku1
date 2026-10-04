package com.example.starter.observation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 披露快照中的冲突裁决摘要：取自不可变解决记录，保持原字段与精度。
 *
 * @param resolutionId    全局唯一解决记录标识
 * @param requestId       生成该记录的请求标识
 * @param baseVersion     基线版本号
 * @param previousVersion 解决前当前版本号
 * @param newVersion      解决后指向的版本号
 * @param conflictFields  服务端重算出的冲突字段名列表
 * @param selections      各冲突字段的人工选择（CURRENT/CANDIDATE）
 * @param operator        操作者标识
 * @param resolvedAtUtc   解决完成时刻（UTC，ISO-8601）
 * @param contentChanged  解决后内容是否发生变化
 */
public record ResolutionSummary(
        String resolutionId,
        String requestId,
        int baseVersion,
        int previousVersion,
        int newVersion,
        List<String> conflictFields,
        Map<String, String> selections,
        String operator,
        Instant resolvedAtUtc,
        boolean contentChanged) {
}