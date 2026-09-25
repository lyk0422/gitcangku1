package com.example.starter.race.api;

import java.util.List;

/**
 * 证据裁决快照响应（写入后不可变；后续处罚/退赛重排不改变本快照）。
 *
 * @param rulingId      裁决批次ID
 * @param raceId        所属赛事ID
 * @param version       裁决后的赛事版本号
 * @param finishTimeMs  裁决的计时组（毫秒）
 * @param orderedBibs   裁决名次顺序（参赛号列表）
 * @param evidenceIds   本批次裁决的全部证据ID列表
 * @param operator      裁决操作者标识
 * @param createdAt     裁决时间，Unix毫秒时间戳
 */
public record EvidenceRulingResponse(
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
