package com.example.starter.race.persistence;

/**
 * runner_finish_event 表行记录：终点（原始完赛）计时不可变事件，
 * 登记带成绩与每次计时修订都追加一行，永不更新或删除。
 *
 * @param id           自增主键
 * @param raceId       所属赛事ID
 * @param bib          选手参赛号
 * @param finishTimeMs 该次登记/修订提交的原始完赛耗时（毫秒）
 * @param requestId    产生该事件的写操作 requestId
 * @param createdAt    终点计时提交时刻，Unix毫秒时间戳（UTC）
 */
public record RunnerFinishEventRow(
        long id,
        String raceId,
        String bib,
        long finishTimeMs,
        String requestId,
        long createdAt
) {
}
