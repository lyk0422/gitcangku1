package com.example.starter.race.dto;

/**
 * 赛事响应。
 *
 * @param raceId  赛事ID
 * @param version 当前版本
 * @param status  赛事状态（OPEN/SEALED）
 */
public record RaceResponse(String raceId, long version, String status) {
}
