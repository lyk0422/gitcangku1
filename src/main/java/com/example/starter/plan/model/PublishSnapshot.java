package com.example.starter.plan.model;

import java.util.List;

/**
 * 发布快照（不可变）：固化发布时的完整编组、危险品类别、各区段限制版本、规则集版本/哈希与校验结论。
 * 后续规则配置、区段清单变更或计划取消均不改写本记录。
 *
 * @param id                   主键
 * @param planId               发布的计划 id
 * @param scheduleKey          计划业务键快照
 * @param planVersion          发布时计划版本
 * @param consist              发布时完整编组（按车厢位置升序，hazardClass 为 null 表示普通车厢）
 * @param ruleVersion          校验时规则集版本（0 表示空规则集）
 * @param rulesHash            校验时规则集 SHA-256
 * @param pathSections         完整区段路径（占用去重、按 sectionId 排序）
 * @param sectionRestrictions  路径上已配置限制区段的开放清单快照（未配置区段不出现，按 sectionId 排序）
 * @param result               校验结论，仅 PASS
 * @param operator             发布操作者
 * @param createdAt            快照创建时刻，UTC 毫秒
 */
public record PublishSnapshot(long id, long planId, String scheduleKey, int planVersion,
                              List<ConsistCar> consist, int ruleVersion, String rulesHash,
                              List<String> pathSections, List<SectionRestriction> sectionRestrictions,
                              String result, String operator, long createdAt) {
}
