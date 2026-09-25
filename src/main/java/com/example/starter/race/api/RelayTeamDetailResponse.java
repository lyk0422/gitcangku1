package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 接力队伍逐棒明细响应。
 *
 * @param raceId      赛事ID
 * @param teamKey     队伍标识
 * @param status      队伍成绩状态：RANKED / UNTIMED / DISQUALIFIED
 * @param totalMillis 接力总用时（毫秒）；未完赛为 null
 * @param foulCount   犯规次数
 * @param legs        逐棒明细，按棒次升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayTeamDetailResponse(
        String raceId,
        String teamKey,
        EntryStatus status,
        Long totalMillis,
        int foulCount,
        List<LegDetail> legs
) {

    /**
     * 单棒明细。
     *
     * @param leg           棒次序号，从1开始
     * @param runner        该棒次登记选手标识
     * @param elapsedMillis 该棒次结束时累计用时（毫秒）；未交接为 null
     * @param splitMillis   该棒次分段用时（毫秒）；无法计算为 null
     * @param zoneMillis    进入该棒次的交接区用时（毫秒，棒次>=2）；未交接/首棒为 null
     * @param foul          进入该棒次的交接是否犯规
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegDetail(
            int leg,
            String runner,
            Long elapsedMillis,
            Long splitMillis,
            Long zoneMillis,
            boolean foul
    ) {
    }
}
