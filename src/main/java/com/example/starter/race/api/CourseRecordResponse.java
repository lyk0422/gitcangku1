package com.example.starter.race.api;

/**
 * 赛道纪录响应（历史链中的一条不可变纪录）。
 *
 * @param recordId       纪录ID（自增，历史链顺序）
 * @param courseKey      所属赛道标识
 * @param raceId         产生该纪录的赛事ID
 * @param bib            创纪录选手参赛号
 * @param timeMs         纪录计时（毫秒），取封榜快照中该选手最终总耗时
 * @param recordClaimKey 纪录认定申请键
 * @param createdAt      纪录切换（认定）时间，Unix毫秒时间戳
 */
public record CourseRecordResponse(
        long recordId,
        String courseKey,
        String raceId,
        String bib,
        long timeMs,
        String recordClaimKey,
        long createdAt
) {
}
