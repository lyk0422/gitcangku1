package com.example.starter.observation;

import java.util.List;
import java.util.Map;

/**
 * 冲突解决响应：描述一次解决提交的结果，同时作为解决历史的查询视图。
 *
 * @param resolutionId    全局唯一解决标识
 * @param observationId   观测记录唯一标识
 * @param baseVersion     解决时使用的基线版本号
 * @param previousVersion 解决前当前版本号
 * @param resultVersion   解决后版本号；未生成新版本时等于 previousVersion
 * @param versionCreated  是否生成了新观测版本：false 表示解决结果与当前完全相同
 * @param conflictFields  服务端重算后的实际冲突字段（按 location、reading、note 顺序）
 * @param selections      各冲突字段的人工选择：字段名 -> CURRENT 或 CANDIDATE
 * @param operator        操作者标识
 * @param resolvedAt      解决时刻（UTC，ISO-8601）
 */
public record ResolutionResponse(
        String resolutionId,
        String observationId,
        int baseVersion,
        int previousVersion,
        int resultVersion,
        boolean versionCreated,
        List<String> conflictFields,
        Map<String, String> selections,
        String operator,
        String resolvedAt) {
}
