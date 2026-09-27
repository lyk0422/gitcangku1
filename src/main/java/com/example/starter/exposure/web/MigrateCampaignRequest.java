package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 公告活动迁移请求。公告编号来自路径；迁移只影响迁移后产生的新预占，
 * 既有预占固化其申请时的活动与小时归属。
 *
 * @param requestId  写操作全局唯一幂等键
 * @param activityId 目标活动编号，必须已存在
 */
public record MigrateCampaignRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String activityId
) {
}
