package com.example.starter.plan.model;

/**
 * 变电所，多区段共享的供电预算主体。
 *
 * @param id           主键
 * @param substationKey 变电所业务键，全局唯一
 * @param powerLimitKw 功率上限，单位千瓦，正整数；任一恒定时间片累计功率不得超过
 * @param version      参数版本，从 1 起，每次调上限成功加一
 */
public record Substation(long id, String substationKey, int powerLimitKw, int version) {
}
