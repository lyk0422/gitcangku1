package com.example.starter.plan.model;

/**
 * 区段-变电所绑定，一个区段最多绑定一个变电所；未绑定区段保持旧模式（仅时隙冲突校验）。
 *
 * @param id           主键
 * @param sectionId    区段 ID，全表唯一
 * @param substationId 目标变电所 id
 */
public record SectionBinding(long id, String sectionId, long substationId) {
}
