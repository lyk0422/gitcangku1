package com.example.starter.api.dto;

import java.util.List;

/**
 * 重解析差异明细（按名称升序）。
 *
 * <p>DRIFTED 报告中 changeType 为 ADDED/REMOVED/CHANGED，reason 为
 * WITHDRAWN（原候选被撤回）/SUPERSEDED（被更高版本取代）/RANGE_NOT_SATISFIED（依赖区间不再满足），
 * oldVersion/newVersion 按类型给出，minimumVersion/maximumVersion/versions 为 null。
 *
 * <p>INFEASIBLE 报告中 changeType 为 BLOCKER，reason 为
 * MISSING_VERSION（区间内有版本缺失）/VERSION_WITHDRAWN（区间内版本已撤回）/
 * RANGE_UNSATISFIABLE（多个已选制品对该名称的区间交集为空）/NO_FEASIBLE_CANDIDATE（有候选但均冲突），
 * minimumVersion/maximumVersion 给出区间，versions 列出缺失或已撤回的版本。
 */
public record ReresolveDiffResponse(
        String name,
        String changeType,
        String reason,
        Integer oldVersion,
        Integer newVersion,
        Integer minimumVersion,
        Integer maximumVersion,
        List<Integer> versions) {
}
