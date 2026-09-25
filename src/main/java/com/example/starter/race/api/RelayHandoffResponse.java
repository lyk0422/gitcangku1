package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 交接提交响应；末棒交接时携带自动生成的队伍完赛结果。
 *
 * @param raceId        赛事ID
 * @param version       提交成功后的赛事版本号
 * @param teamKey       队伍标识
 * @param leg           交接棒次
 * @param receiver      接棒选手标识
 * @param elapsedMillis 接棒选手累计用时（毫秒）
 * @param zoneMillis    交接区实际用时（毫秒）
 * @param foul          本次交接是否犯规（交接区用时超上限，不可逆）
 * @param teamFoulCount 该队累计犯规次数（含本次）
 * @param finished      本次交接是否触发生成队伍完赛记录（末棒）
 * @param totalMillis   队伍接力总用时（毫秒）；未完赛为 null
 * @param teamStatus    队伍成绩状态；未完赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayHandoffResponse(
        String raceId,
        int version,
        String teamKey,
        int leg,
        String receiver,
        long elapsedMillis,
        long zoneMillis,
        boolean foul,
        int teamFoulCount,
        boolean finished,
        Long totalMillis,
        EntryStatus teamStatus
) {
}
