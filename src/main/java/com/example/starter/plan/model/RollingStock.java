package com.example.starter.plan.model;

/**
 * 车底登记与最小周转参数。
 *
 * @param id                   主键
 * @param stockKey             车底标识，全局唯一
 * @param minTurnaroundMinutes 最小周转分钟数（1～240）
 * @param version              周转参数版本，修改成功一次加一
 */
public record RollingStock(long id, String stockKey, int minTurnaroundMinutes, int version) {
}
