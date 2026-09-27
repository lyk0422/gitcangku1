package com.example.starter.plan.model;

/**
 * 限制区段开放清单中的一个“区段-允许类别”条目。
 *
 * @param configVersionId 所属配置版本 id
 * @param sectionId       限制区段 ID
 * @param hazardClass     开放（允许通过）的危险品类别
 */
public record HazardSectionOpen(long configVersionId, String sectionId, String hazardClass) {
}
