package com.example.starter.race.api;

/**
 * 赛道纪录响应（历史链中的一个节点）。
 *
 * @param recordId  纪录ID
 * @param courseKey 所属赛道标识
 * @param seq       链内序号，从1开始
 * @param raceId    创建赛事ID
 * @param bib       创纪录选手参赛号
 * @param timeMs    纪录计时（封榜快照最终计时，毫秒）
 * @param claimedAt 切换时刻，Unix毫秒时间戳
 */
public record CourseRecordResponse(String recordId, String courseKey, int seq, String raceId,
                                   String bib, long timeMs, long claimedAt) {
}
