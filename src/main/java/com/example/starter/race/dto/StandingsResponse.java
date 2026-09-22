package com.example.starter.race.dto;

import java.util.List;

/**
 * 即时成绩响应。
 *
 * @param raceId    赛事ID
 * @param version   当前赛事版本
 * @param status    赛事状态（OPEN/SEALED）
 * @param standings 成绩条目：先按名次升序（并列按参赛号字典序），后接UNTIMED、DISQUALIFIED（均按参赛号字典序）
 */
public record StandingsResponse(String raceId, long version, String status,
                                List<StandingEntry> standings) {
}
