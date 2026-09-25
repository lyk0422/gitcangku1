package com.example.starter.race.persistence;

/**
 * course_record 表行记录：纪录历史链中的一个不可变节点。
 *
 * @param recordId  纪录ID，全局唯一
 * @param claimKey  认定申请键，全局唯一（幂等键）
 * @param courseKey 所属赛道标识
 * @param seq       链内序号，从1开始
 * @param raceId    产生该纪录的赛事ID
 * @param bib       创纪录选手参赛号
 * @param timeMs    纪录计时（封榜快照最终计时，毫秒）
 * @param claimedAt 认定切换时刻，Unix毫秒时间戳
 */
public record CourseRecordRow(String recordId, String claimKey, String courseKey, int seq,
                              String raceId, String bib, long timeMs, long claimedAt) {
}
