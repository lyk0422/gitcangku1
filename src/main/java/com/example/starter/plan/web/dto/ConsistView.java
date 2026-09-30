package com.example.starter.plan.web.dto;

/**
 * 编组视图：计划登记的编组属性快照。
 *
 * @param lengthM            编组长度，单位米
 * @param powerSupplyType    编组需求供电制式代码：AC25KV / DC1500V / DC3000V / NONE
 * @param accessibleRequired 编组是否有无障碍登乘需求
 */
public record ConsistView(Integer lengthM, String powerSupplyType, Boolean accessibleRequired) {
}
