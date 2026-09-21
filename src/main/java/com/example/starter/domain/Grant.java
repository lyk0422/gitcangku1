package com.example.starter.domain;

import java.time.Instant;

/**
 * 授权：关联频道与普通素材，有效区间为左闭右开 [validFrom, validTo)。
 * 创建后只能撤销，不能修改。
 *
 * @param id        授权 ID，服务端生成
 * @param channelId 频道 ID
 * @param assetId   素材 ID
 * @param validFrom 生效时间（含），UTC 时刻
 * @param validTo   失效时间（不含），UTC 时刻
 * @param revoked   是否已撤销；撤销后不可恢复
 */
public record Grant(String id, String channelId, String assetId,
                    Instant validFrom, Instant validTo, boolean revoked) {

    /**
     * 判断该授权是否未撤销且有效区间完整覆盖 [start, end)。
     */
    public boolean covers(Instant start, Instant end) {
        return !revoked && !validFrom.isAfter(start) && !validTo.isBefore(end);
    }
}
