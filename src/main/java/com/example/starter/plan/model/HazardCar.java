package com.example.starter.plan.model;

/**
 * 计划内一节危险品车厢。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param seq         编组项在计划内的提交序号，从 0 开始
 * @param carPosition 车厢位置（从 1 开始），同一计划内唯一
 * @param hazardClass 危险品类别代码
 */
public record HazardCar(long id, long planId, int seq, int carPosition, String hazardClass) {
}
