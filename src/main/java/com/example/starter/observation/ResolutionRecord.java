package com.example.starter.observation;

/**
 * 冲突解决记录：对应 observation_resolution 表的一行，成功解决后不可变。
 *
 * @param resolutionId       全局唯一解决标识
 * @param observationId      观测记录唯一标识
 * @param baseVersion        解决时使用的基线版本号
 * @param previousVersion    解决前当前版本号
 * @param resultVersion      解决后版本号；未生成新版本时等于 previousVersion
 * @param versionCreated     是否生成了新观测版本
 * @param candidateLocation  地点候选值（完整提交原文）
 * @param candidateReading   读数候选值（十进制字符串原文）
 * @param candidateNote      备注候选值
 * @param conflictFields     本次实际冲突字段，逗号分隔；无冲突时为空串
 * @param selectionsJson     各冲突字段的人工选择（JSON：字段名 -> CURRENT/CANDIDATE）
 * @param fingerprint        解决参数指纹，同一 resolutionId 异参判定 409
 * @param operator           操作者标识
 * @param resolvedAt         解决时刻（UTC，ISO-8601）
 */
public record ResolutionRecord(
        String resolutionId,
        String observationId,
        int baseVersion,
        int previousVersion,
        int resultVersion,
        boolean versionCreated,
        String candidateLocation,
        String candidateReading,
        String candidateNote,
        String conflictFields,
        String selectionsJson,
        String fingerprint,
        String operator,
        String resolvedAt) {
}
