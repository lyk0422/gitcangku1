package com.example.starter.crosssection;

import java.time.Instant;
import java.util.List;

/**
 * 不可变断面冲突裁决记录：对应 cross_section_resolution 表的一行，成功裁决后原子落库，永不修改。
 *
 * @param resolutionId        全局唯一裁决记录标识
 * @param observationId       断面观测点唯一标识
 * @param requestId           生成该记录的请求标识
 * @param baseVersion         基线版本号
 * @param previousVersion     裁决前当前版本号
 * @param newVersion          裁决后指向的版本号（无变化裁决时等于 previousVersion）
 * @param candidateSectionNo  候选断面号完整值
 * @param candidateXMm        候选横向坐标完整值（整数毫米）
 * @param candidateZMm        候选地面高程完整值（整数毫米）
 * @param conflictFields      服务端重算出的冲突字段名列表
 * @param fieldSelections     各冲突字段的人工选择（CURRENT/CANDIDATE 的 JSON 原文）
 * @param operator            操作者标识
 * @param resolvedAtUtc       裁决完成时刻（UTC）
 * @param contentChanged      裁决后内容是否变化：false 表示未生成新版本
 */
public record CrossSectionResolutionRecord(
        String resolutionId,
        String observationId,
        String requestId,
        int baseVersion,
        int previousVersion,
        int newVersion,
        String candidateSectionNo,
        int candidateXMm,
        int candidateZMm,
        List<String> conflictFields,
        String fieldSelections,
        String operator,
        Instant resolvedAtUtc,
        boolean contentChanged) {
}
