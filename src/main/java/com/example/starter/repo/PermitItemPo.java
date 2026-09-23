package com.example.starter.repo;

/**
 * 豁免包区域项持久化记录。
 *
 * @param permitId      所属豁免包标识
 * @param regionKey     区域标识（对应禁飞区 zoneId），包内唯一
 * @param regionVersion 审批时区域版本（禁飞区创建生效的全局空域版本）
 * @param validFrom     UTC 有效区间起点（epoch 毫秒，含端点）
 * @param validTo       UTC 有效区间终点（epoch 毫秒，含端点）
 * @param quota         签发额度，1~100
 * @param remaining     当前剩余额度；撤销后冻结不再变化
 */
public record PermitItemPo(String permitId, String regionKey, long regionVersion,
                           long validFrom, long validTo, int quota, int remaining) {
}
