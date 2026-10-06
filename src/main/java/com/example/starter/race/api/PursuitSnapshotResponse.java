package com.example.starter.race.api;

import java.util.List;

/**
 * 追逐赛封榜快照响应：冻结的追逐对裁定（交会分数）与完整轨迹点。
 *
 * @param raceId   赛事ID
 * @param version  封榜后的赛事版本号
 * @param sealedAt 封榜时间，Unix毫秒时间戳
 * @param pairs    冻结的追逐对与交会分数（按序号升序）
 * @param samples  冻结的完整轨迹点（按参赛号与采样序号升序）
 */
public record PursuitSnapshotResponse(
        String raceId,
        int version,
        long sealedAt,
        List<PursuitSnapshotPairResponse> pairs,
        List<PursuitSnapshotSampleResponse> samples
) {
}
