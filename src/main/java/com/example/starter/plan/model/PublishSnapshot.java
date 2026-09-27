package com.example.starter.plan.model;

/**
 * 发布快照主记录。每次发布（含改签发布）固化一份，追加后不可变、不删除；
 * 取消计划不删除其快照，站台规则修订不改写快照内容。
 *
 * @param id          主键，亦为快照历史稳定排序依据
 * @param planId      被发布计划 id，关联 rail_day_plan.id
 * @param scheduleKey 发布时计划业务键快照
 * @param publishedBy 发布操作者；null 表示请求未登记操作者
 * @param publishedAt 发布时刻，UTC 毫秒
 */
public record PublishSnapshot(long id, long planId, String scheduleKey, String publishedBy,
                              long publishedAt) {
}
