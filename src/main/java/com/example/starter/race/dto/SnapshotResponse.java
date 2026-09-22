package com.example.starter.race.dto;

import java.util.List;

/**
 * 封榜/快照响应。
 *
 * @param raceId   赛事ID
 * @param version  封榜时的赛事版本
 * @param status   赛事状态，恒为SEALED
 * @param snapshot 封榜时保存的只读成绩快照
 */
public record SnapshotResponse(String raceId, long version, String status,
                               List<StandingEntry> snapshot) {
}
