package com.example.starter.plan.model;

/**
 * 危险品编组中的一节车厢。
 *
 * @param id           主键
 * @param planId       所属计划 id
 * @param position     车厢编组位置（车厢号，从 1 开始），同一计划内唯一
 * @param hazardClass  危险品类别代码；{@code null} 表示普通车厢（非危险品，非未知）
 */
public record ConsistCar(long id, long planId, int position, String hazardClass) {
}
