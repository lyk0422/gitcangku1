package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.Placement;

/**
 * 展示位视图。
 *
 * @param campaignId    所属公告编号
 * @param placementCode 展示位编号（DEFAULT 为随公告创建的默认展示位）
 * @param dailyCap      该展示位每 UTC 日额度，单位次
 * @param configVersion 该展示位落库后公告的配置版本号
 * @param createdAtUtc  创建时刻，epoch 毫秒，UTC
 */
public record PlacementResponse(
        String campaignId,
        String placementCode,
        int dailyCap,
        int configVersion,
        long createdAtUtc
) {
    public static PlacementResponse from(Placement placement) {
        return new PlacementResponse(
                placement.campaignId(),
                placement.placementCode(),
                placement.dailyCap(),
                placement.configVersion(),
                placement.createdAtUtc());
    }
}
