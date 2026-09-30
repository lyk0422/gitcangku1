package com.example.starter.plan.model;

/**
 * 线路区段登记：把计划占用中的区段 ID 映射到所属线路与所需线路等级。
 *
 * @param sectionId     区段 ID（与占用明细 section_id 对应），主键
 * @param lineId        所属线路 ID
 * @param requiredGrade 值乘该区段要求的最低线路等级
 */
public record LineSection(String sectionId, String lineId, int requiredGrade) {
}
