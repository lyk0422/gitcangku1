package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核时效诊断。保留审核原结论，并逐项给出审核时版本与当前版本的比对；
 * 任一版本不再匹配时 effectiveConclusion 为 STALE。
 *
 * @param reviewId            审核记录标识
 * @param conclusion          保存时的原结论（永不改变）
 * @param effectiveConclusion 当前有效结论：版本全部匹配时为原结论，否则 STALE
 * @param stale               是否已失效
 * @param versions            各版本比对明细（reviewed=审核时固化值，current=当前值，match=是否一致）
 * @param weatherViolations   审核时固化的气象不达标明细；未做气象检查为 null
 */
public record ReviewDiagnosticsDto(
        String reviewId,
        String conclusion,
        String effectiveConclusion,
        Boolean stale,
        List<VersionCheck> versions,
        List<WeatherViolationDto> weatherViolations) {

    /**
     * 单项版本比对。
     *
     * @param name     版本名称：routeVersion/airspaceVersion/forecastVersion/standardsVersion
     * @param reviewed 审核时固化的版本值
     * @param current  当前版本值
     * @param match    是否一致
     */
    public record VersionCheck(String name, Long reviewed, Long current, Boolean match) {
    }
}
