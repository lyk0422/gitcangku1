package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 披露快照中嵌入的冲突裁决摘要：取自发布时点已落库的不可变解决记录。
 *
 * @param resolutionId    全局唯一解决记录标识
 * @param baseVersion     解决时重读的基线版本号
 * @param previousVersion 解决前当前版本号
 * @param newVersion      解决后指向的版本号
 * @param conflictFields  服务端重算出的冲突字段名列表
 * @param operator        执行冲突解决的操作者标识
 * @param resolvedAtUtc   解决完成时刻（UTC）
 */
public record ResolutionSummary(
        String resolutionId,
        int baseVersion,
        int previousVersion,
        int newVersion,
        List<String> conflictFields,
        String operator,
        Instant resolvedAtUtc) {

    /**
     * 由不可变解决记录构造摘要。
     */
    public static ResolutionSummary of(ResolutionRecord record) {
        return new ResolutionSummary(record.resolutionId(), record.baseVersion(), record.previousVersion(),
                record.newVersion(), List.copyOf(record.conflictFields()), record.operator(),
                record.resolvedAtUtc());
    }
}