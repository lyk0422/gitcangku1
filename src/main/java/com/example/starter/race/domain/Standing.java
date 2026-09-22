package com.example.starter.race.domain;

import java.util.List;

/**
 * 一份完整成绩榜：即时成绩或封榜只读快照。
 *
 * @param raceId   赛事ID
 * @param version  计算该榜单时的赛事版本号
 * @param status   赛事状态
 * @param sealedAt 封榜时间（Unix 毫秒时间戳）；未封榜为 null
 * @param entries  按展示顺序排列的成绩条目（名次顺序，并列按参赛号字典序，未排名按参赛号字典序）
 */
public record Standing(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<ResultEntry> entries
) {
}
