package com.example.starter.plan.model;

/**
 * 区段走廊等级登记记录。
 *
 * @param sectionId 区段 ID
 * @param priority  走廊等级 1～5，数值越大优先级越高；未登记区段按最低等级 1 参与抢占判定
 */
public record SectionPriority(String sectionId, int priority) {
}
