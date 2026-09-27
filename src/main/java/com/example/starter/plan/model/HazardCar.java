package com.example.starter.plan.model;

/**
 * 编组中的一节危险品车厢，属于计划内某列车的完整编组。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param trainNo     列车编号，须与计划占用中的某条 train_no 对应
 * @param position    车厢在编组中的位置，从 1 开始的正整数；同一计划同一列车位置重复为业务冲突（422）
 * @param hazardClass 危险品类别代码，非空；是否可经限制区段以该区段开放清单为准
 */
public record HazardCar(long id, long planId, String trainNo, int position, String hazardClass) {
}
