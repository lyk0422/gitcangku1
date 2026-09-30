package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 公告活动迁移请求。迁移只影响迁移之后的新预占；既有预占固化原活动与小时归属。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param activityId 目标活动编号；null 或空串表示把公告移出活动（此后不再有小时名额约束）
 */
public record MigrateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Size(max = 64) String activityId
) {
}
