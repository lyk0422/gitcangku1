package com.example.starter.race.persistence;

import java.util.List;

/**
 * evidence_ruling 表行记录（不可变证据裁决快照）。
 *
 * @param rulingId      裁决批次ID，全局唯一
 * @param raceId        所属赛事ID
 * @param version       裁决后的赛事版本号
 * @param finishTimeMs  裁决的计时组（毫秒）
 * @param orderedBibs   裁决名次顺序（参赛号列表，索引0为该计时组第1名），写入后不可变
 * @param evidenceIds   本批次裁决的全部证据ID列表，写入后不可变
 * @param operator      裁决操作者标识
 * @param createdAt     裁决时间，Unix毫秒时间戳
 */
public record EvidenceRulingRow(
        String rulingId,
        String raceId,
        int version,
        long finishTimeMs,
        List<String> orderedBibs,
        List<String> evidenceIds,
        String operator,
        long createdAt
) {
}
