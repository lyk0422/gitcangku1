package com.example.starter.repo;

/**
 * 豁免包区域项持久化记录。同一豁免包内 regionKey 不可重复；
 * 有效区间为 UTC 半开区间 [validFrom, validTo)；剩余额度初始等于 quota。
 *
 * @param permitKey     所属豁免包标识
 * @param regionKey     区域（禁飞区）标识
 * @param regionVersion 区域版本，审核时须与命中区域的生效空域版本一致
 * @param validFrom     有效起始时间（含），epoch 毫秒（UTC）
 * @param validTo       有效结束时间（不含），epoch 毫秒（UTC）
 * @param quota         签发额度，1~100 次
 * @param remaining     当前剩余额度（次数）；撤销豁免包不回写历史核销
 * @param touch         仅用于审核事务对该项加行级排他锁的计数器，无业务含义
 */
public record PermitItemPo(String permitKey, String regionKey, long regionVersion,
                           long validFrom, long validTo, int quota, int remaining,
                           long touch) {
}
