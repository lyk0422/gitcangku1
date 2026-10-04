package com.example.starter.plan.model;

/**
 * 区段牵引档案。
 *
 * @param id            主键
 * @param sectionId     区段 ID，全局唯一
 * @param electrified   是否供电；电力车要求占用全程为 true
 * @param dieselAllowed 是否允许柴油车通行；柴油车要求占用全程为 true
 * @param version       档案版本，从 1 开始，每次变更成功加一
 */
public record SectionProfile(long id, String sectionId, boolean electrified, boolean dieselAllowed,
                             int version) {
}