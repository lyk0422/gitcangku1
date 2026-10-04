package com.example.starter.plan.model;

/**
 * 发布/改签成功时冻结的牵引快照（不可变）。后续档案合法变更不改写历史快照。
 *
 * @param id             主键
 * @param planId         所属计划 id
 * @param trainNo        列车编号（发布时刻占用涉及的列车）
 * @param tractionMode   发布时刻冻结的牵引方式
 * @param sectionId      区段 ID（发布时刻占用涉及的区段）
 * @param electrified    发布时刻冻结的是否供电
 * @param dieselAllowed  发布时刻冻结的是否允许柴油
 * @param sectionVersion 发布时刻冻结的区段档案版本
 * @param createdAt      快照创建时刻，UTC 毫秒
 */
public record TractionSnapshot(long id, long planId, String trainNo, TractionMode tractionMode,
                               String sectionId, boolean electrified, boolean dieselAllowed,
                               int sectionVersion, long createdAt) {
}