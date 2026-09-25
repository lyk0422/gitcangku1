package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一次交接提交的结果。
 *
 * @param teamKey         交接队伍标识
 * @param legNo           交接棒次（接棒选手棒次）
 * @param bib             接棒选手参赛号
 * @param elapsedMillis   接棒选手累计耗时（毫秒）
 * @param handoffMillis   交接区实际用时（毫秒）
 * @param foul            本次交接是否犯规
 * @param completedAt     交接完成服务端时刻，Unix毫秒时间戳
 * @param finished        本次交接是否为末棒并已生成完赛记录
 * @param totalFouls      该队当前累计犯规次数
 * @param totalElapsedMillis 完赛总用时（末棒累计耗时，毫秒）；未完赛为 null
 * @param teamStatus      本次交接后队伍状态：RACING / RANKED / DISQUALIFIED
 * @param version         提交后的赛事版本
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HandoffResponse(
        String teamKey,
        int legNo,
        String bib,
        long elapsedMillis,
        long handoffMillis,
        boolean foul,
        long completedAt,
        boolean finished,
        int totalFouls,
        Long totalElapsedMillis,
        String teamStatus,
        int version) {
}
