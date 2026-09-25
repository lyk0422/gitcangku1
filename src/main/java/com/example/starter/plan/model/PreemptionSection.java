package com.example.starter.plan.model;

/**
 * 抢占涉及区段明细：抢占方与被抢占计划实际冲突的区段及其当时等级。
 *
 * @param id           主键
 * @param preemptionId 所属抢占记录 id
 * @param sectionId    涉及区段 ID
 * @param sectionLevel 该区段在抢占时刻的登记等级
 */
public record PreemptionSection(long id, long preemptionId, String sectionId, int sectionLevel) {
}
