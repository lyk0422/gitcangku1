package com.example.starter.consent.catalog.dto;

/**
 * 记录映射结果：MAPPED 命中唯一新用途；UNMAPPED 无覆盖（含属性缺失）保留旧用途；
 * RETAINED 属于已撤回/已迁移授权的隔离数据，强制保留历史旧用途。
 */
public enum MappingResult {
    MAPPED,
    UNMAPPED,
    RETAINED
}
