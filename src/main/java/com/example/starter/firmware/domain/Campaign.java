package com.example.starter.firmware.domain;

/**
 * 投放活动，队列迁移的作用域。
 *
 * @param id     投放活动ID
 * @param name   活动名称
 * @param status 状态：ACTIVE 进行中，ENDED 已结束（终态）
 */
public record Campaign(long id, String name, CampaignStatus status) {
}
