package com.example.starter.snow.model;

import java.time.Instant;

/**
 * 解除决定版本行：第二人确认后生成，历史版本永不删除。
 *
 * @param id 主键
 * @param reviewId 关联审核主键
 * @param regionId 控制区标识
 * @param version 区域内递增版本号
 * @param createdAt 生成时间（UTC）
 */
public record DecisionRec(long id, long reviewId, String regionId, long version, Instant createdAt) {
}