package com.example.starter.plan.model;

/**
 * 变电所主记录，多个区段可共享同一变电所。
 *
 * @param id            主键
 * @param substationKey 变电所业务键，全局唯一
 * @param powerCapKw    功率上限，正整数千瓦；任一时间片累计功率不得超过该值
 * @param version       上限版本，从 1 起，调参成功一次加一
 */
public record Substation(long id, String substationKey, int powerCapKw, int version) {
}
