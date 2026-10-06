package com.example.starter.exposure.domain;

import java.util.List;

/**
 * 公告 PO。公告以 campaignId 唯一，创建时固定每 UTC 日总额度与每访客每日上限；
 * 可固定 2~5 个有序轮换创意编号，未配置（空列表）则仅接受普通申请。
 *
 * @param campaignId          公告编号，全局唯一
 * @param dailyTotalCap       每 UTC 日总额度，单位次，取值 1～100000
 * @param perVisitorDailyCap  每访客每 UTC 日上限，单位次，取值 1～100000
 * @param creativeIds         有序轮换创意编号（2~5 个，顺序即轮换顺序）；空列表表示不支持轮换申请
 * @param createdAtUtc        创建时刻（epoch 毫秒，UTC）
 */
public record Campaign(
        String campaignId,
        int dailyTotalCap,
        int perVisitorDailyCap,
        List<String> creativeIds,
        long createdAtUtc
) {
    public Campaign {
        creativeIds = creativeIds == null ? List.of() : List.copyOf(creativeIds);
    }

    /** 是否配置了轮换创意（2~5 个有序编号）。 */
    public boolean supportsRotation() {
        return creativeIds.size() >= 2;
    }
}
