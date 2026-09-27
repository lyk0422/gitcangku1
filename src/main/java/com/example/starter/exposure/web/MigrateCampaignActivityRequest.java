package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 公告活动迁移请求。只影响迁移后的新预占，既有预占的活动与小时归属固化不变。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param activityId 目标活动编号；null 表示移出活动
 */
public record MigrateCampaignActivityRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String activityId
) {
}
