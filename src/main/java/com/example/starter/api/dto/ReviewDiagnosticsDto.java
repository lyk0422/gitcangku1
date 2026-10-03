package com.example.starter.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 审查时效诊断：保留原结论，逐项对比审查时固化版本与当前版本。
 * 只读查询，不改变任何状态。
 *
 * @param reviewId                审查记录标识
 * @param routeId                 航线标识
 * @param conclusion              保存时的原结论（永不被改写）
 * @param effective               当前时效：CURRENT 全部版本仍匹配 / STALE 任一版本已偏离
 * @param routeVersionAtReview    审查时固化的航线版本
 * @param routeVersionCurrent     当前航线版本；航线已删除为 null
 * @param airspaceVersionAtReview 审查时固化的空域版本
 * @param airspaceVersionCurrent  当前空域版本
 * @param weatherVersionAtReview  审查时固化的气象版本；null 表示无气象评估
 * @param weatherVersionCurrent   当前气象（值班）版本
 * @param weatherSnapshot         审查时固化的标准与预测快照 JSON；null 表示无气象评估
 */
public record ReviewDiagnosticsDto(
        String reviewId,
        String routeId,
        String conclusion,
        String effective,
        int routeVersionAtReview,
        Integer routeVersionCurrent,
        long airspaceVersionAtReview,
        long airspaceVersionCurrent,
        Long weatherVersionAtReview,
        long weatherVersionCurrent,
        JsonNode weatherSnapshot) {
}