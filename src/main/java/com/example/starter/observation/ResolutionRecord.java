package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变冲突解决记录：对应 conflict_resolution 表的一行，成功解决后原子落库，永不修改。
 * 带量纲记录额外冻结候选原始单位、候选规范值与解决后的精确规范值。
 *
 * @param resolutionId                 全局唯一解决记录标识
 * @param observationId                观测记录唯一标识
 * @param requestId                    生成该记录的请求标识
 * @param baseVersion                  基线版本号
 * @param previousVersion              解决前当前版本号
 * @param newVersion                   解决后指向的版本号（无变化解决时等于 previousVersion）
 * @param candidateLocation            候选地点完整值
 * @param candidateReading             候选读数完整值（十进制原文）
 * @param candidateNote                候选备注完整值
 * @param conflictFields               服务端重算出的冲突字段名列表
 * @param fieldSelections              各冲突字段的人工选择（CURRENT/CANDIDATE 的 JSON 原文）
 * @param operator                     操作者标识
 * @param resolvedAtUtc                解决完成时刻（UTC）
 * @param contentChanged               解决后内容是否发生变化：false 表示未生成新观测版本
 * @param dimension                    读数量纲：LENGTH / TEMPERATURE；null 表示旧无单位读数
 * @param candidateReadingUnit         候选读数原始单位：m / cm / C / K；null 表示无单位
 * @param candidateReadingNormalized   候选读数的精确规范值（长度 m、温度 C）；null 表示无量纲
 * @param resolvedReadingNormalized    解决后冻结的精确规范读数；null 表示无量纲
 */
public record ResolutionRecord(
        String resolutionId,
        String observationId,
        String requestId,
        int baseVersion,
        int previousVersion,
        int newVersion,
        String candidateLocation,
        String candidateReading,
        String candidateNote,
        List<String> conflictFields,
        String fieldSelections,
        String operator,
        Instant resolvedAtUtc,
        boolean contentChanged,
        String dimension,
        String candidateReadingUnit,
        String candidateReadingNormalized,
        String resolvedReadingNormalized) {
}
