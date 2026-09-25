package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 接力逐棒明细中的单棒。
 *
 * @param legNo          棒次序号（从1开始）
 * @param bib            该棒次选手参赛号
 * @param elapsedMillis  该棒次记录的累计耗时（毫秒）；首棒未交接/该棒未交接为 null
 * @param handoffMillis  进入该棒次的交接区用时（毫秒）；首棒/未交接为 null
 * @param completedAt    该棒次交接完成服务端时刻（Unix毫秒）；首棒/未交接为 null
 * @param foul           进入该棒次的交接是否犯规
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayLegDetailResponse(
        int legNo,
        String bib,
        Long elapsedMillis,
        Long handoffMillis,
        Long completedAt,
        boolean foul) {
}
