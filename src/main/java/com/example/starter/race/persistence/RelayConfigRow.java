package com.example.starter.race.persistence;

/**
 * relay_config 表行记录；存在即表示赛事为接力模式，配置后不可修改。
 *
 * @param raceId          赛事ID
 * @param legCount        棒次数，取值2~8
 * @param exchangeLimitMs 交接区用时上限（毫秒，1~10000），超过即判犯规
 * @param createdAt       配置时间，Unix毫秒时间戳
 */
public record RelayConfigRow(String raceId, int legCount, long exchangeLimitMs, long createdAt) {
}
