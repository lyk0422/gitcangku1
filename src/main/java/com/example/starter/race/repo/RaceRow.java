package com.example.starter.race.repo;

/**
 * 赛事行。
 *
 * @param raceId  赛事ID
 * @param version 当前版本
 * @param status  状态（OPEN/SEALED）
 */
public record RaceRow(String raceId, long version, String status) {
}
