package com.example.starter.plan.model;

/**
 * 单线区段换向配置。版本从 1 起，每次更新加一；更新须携带 expectedVersion。
 *
 * @param sectionId        单线区段 ID，全局唯一
 * @param clearanceSeconds 换向清空间隔，整秒，范围 1 至 600；
 *                         相邻反向占用要求后者开始减前者结束不小于该值
 * @param version          配置版本，从 1 起
 */
public record SectionConfig(String sectionId, int clearanceSeconds, int version) {
}
