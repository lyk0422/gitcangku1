package com.example.starter.plan.model;

/**
 * 车底主数据：车底标识与版本化的最小周转分钟数。
 *
 * @param id                     主键
 * @param stockNo                车底标识，业务键，全局唯一
 * @param minTurnaroundMinutes   最小周转分钟数，取值 1～240
 * @param version                车底版本；周转参数修改成功一次加一，修改须携带 expectedVersion
 */
public record RollingStock(long id, String stockNo, int minTurnaroundMinutes, int version) {
}
