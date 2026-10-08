package com.example.starter.crosssection;

import java.util.List;

/**
 * 未解决冲突标记：整组三方合并出现字段冲突时落库，裁决或内容变更后清除。
 *
 * @param observationId     存在未解决冲突的断面观测点标识
 * @param baseVersion       冲突候选所基于的基线版本号
 * @param conflictVersion   冲突发生时服务端当前版本号
 * @param candidateSectionNo 冲突候选断面号完整值
 * @param candidateXMm      冲突候选横向坐标完整值（整数毫米）
 * @param candidateZMm      冲突候选地面高程完整值（整数毫米）
 * @param conflictFields    服务端重算出的冲突字段名列表
 * @param requestId         最近一次写入该标记的冲突请求标识
 */
public record PendingConflict(
        String observationId,
        int baseVersion,
        int conflictVersion,
        String candidateSectionNo,
        int candidateXMm,
        int candidateZMm,
        List<String> conflictFields,
        String requestId) {
}
