package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * reclass_record 表行记录（不可变重分记录，只插入不更新）。
 *
 * @param batchId          批准批次ID（批准请求的 reclassKey）
 * @param bib              被重分选手参赛号
 * @param raceId           所属赛事ID
 * @param fromCategory     迁移前所属组别代码；null 表示迁移前未分组
 * @param toCategory       迁移后所属组别代码
 * @param prevRank         迁移前在来源组别榜单中的名次；未排名或未分组为 null
 * @param prevStatus       迁移前成绩状态
 * @param prevFinishTimeMs 迁移前原始完赛耗时（毫秒）；计时缺失为 null
 * @param prevPenaltyMs    迁移前生效加时合计毫秒数
 * @param prevTotalTimeMs  迁移前总耗时（毫秒）；迁移前未排名为 null
 * @param createdAt        迁移时间，Unix毫秒时间戳
 */
public record ReclassRecordRow(
        String batchId,
        String bib,
        String raceId,
        String fromCategory,
        String toCategory,
        Integer prevRank,
        EntryStatus prevStatus,
        Long prevFinishTimeMs,
        Long prevPenaltyMs,
        Long prevTotalTimeMs,
        long createdAt
) {
}
