package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 公告版本撤回请求。发布方提交撤回键、截点与当前公告版本。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param withdrawalKey   撤回键，全局唯一；同键同参重放原结果，异参 409
 * @param campaignId      公告编号
 * @param campaignVersion 发布方认知的当前公告版本，须与服务端一致否则 409
 * @param cutoffAtUtc     撤回截点，epoch 毫秒，UTC；仅 occurredAt 早于该值的回执可合法确认
 */
public record WithdrawCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String withdrawalKey,
        @NotBlank @Size(max = 64) String campaignId,
        @NotNull @Positive Integer campaignVersion,
        @NotNull Long cutoffAtUtc
) {
}
