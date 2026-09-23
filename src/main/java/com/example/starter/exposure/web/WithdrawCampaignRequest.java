package com.example.starter.exposure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤回公告版本请求。撤回后该版本原子禁止新预占，
 * 全部 PENDING 预占冻结为 SETTLING 快照，已确认曝光不回退。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param withdrawalKey   撤回键，全局唯一
 * @param campaignId      公告编号
 * @param campaignVersion 当前公告版本；与服务端不一致返回 409
 * @param cutoffAt        撤回截点（epoch 毫秒，UTC）：仅 occurredAt 早于该截点的回执可确认
 */
public record WithdrawCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String withdrawalKey,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Min(1) Integer campaignVersion,
        @NotNull Long cutoffAt
) {
}
