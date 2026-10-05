package com.example.starter.observation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 披露快照中的单条冲突裁决摘要（不可变，随首次发布固化）。
 *
 * @param resolutionId    全局唯一解决记录标识
 * @param baseVersion     基线版本号
 * @param previousVersion 解决前当前版本号
 * @param newVersion      解决后指向的版本号
 * @param conflictFields  服务端重算出的冲突字段名列表
 * @param selections      各冲突字段的人工选择（CURRENT/CANDIDATE）
 * @param operator        执行冲突解决的操作者标识
 * @param resolvedAtUtc   解决完成时刻（UTC，ISO-8601）
 */
public record DisclosureResolutionItem(
        String resolutionId,
        int baseVersion,
        int previousVersion,
        int newVersion,
        List<String> conflictFields,
        Map<String, String> selections,
        String operator,
        Instant resolvedAtUtc) {
}
