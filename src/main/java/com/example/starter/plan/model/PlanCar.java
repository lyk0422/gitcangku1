package com.example.starter.plan.model;

/**
 * 计划危险品编组中一节车厢。
 *
 * @param id          主键
 * @param planId      所属计划 id
 * @param carPosition 车厢位置（编组内序号，0 起），同一计划内唯一
 * @param category    危险品类别代码
 */
public record PlanCar(long id, long planId, int carPosition, String category) {
}
