package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个选手区间查询响应：按计时点顺序稳定返回每个检查点及完赛点的区间情况。
 *
 * @param raceId   赛事ID
 * @param bib      参赛号
 * @param version  查询时的赛事版本（只读查询不修改版本）
 * @param points   按 position 升序的计时点明细（检查点在前，完赛点 FINISH 最后）；
 *                 未配置检查点的赛事仅含完赛点
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerIntervalsResponse(
        String raceId,
        String bib,
        int version,
        List<IntervalPassResponse> points
) {
}
