package com.example.starter.exposure.domain;

/**
 * 公告归因窗口版本历史 PO，追加写入、不可变。预占创建时快照对应的版本号与窗口分钟数，
 * 后续调整窗口只追加新版本，不重算历史归因。
 *
 * @param campaignId                所属公告编号
 * @param version                   窗口版本号，同公告内唯一且从 1 起递增
 * @param attributionWindowMinutes  该版本 UTC 归因窗口分钟数（要求值），1～1440
 * @param operatorId                生效该版本的操作者编号；公告创建产生的 V1 为 null
 * @param createdAtUtc              版本生效时刻（epoch 毫秒，UTC）
 */
public record CampaignWindowVersion(
        String campaignId,
        int version,
        int attributionWindowMinutes,
        String operatorId,
        long createdAtUtc
) {
}
