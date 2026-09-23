package com.example.starter.api.dto;

/**
 * 豁免包区域项结果（签发与查询共用）。
 *
 * @param regionKey     区域标识
 * @param regionVersion 审批时区域版本
 * @param validFrom     UTC 有效区间起点，epoch 毫秒（含端点）
 * @param validTo       UTC 有效区间终点，epoch 毫秒（含端点）
 * @param quota         签发额度
 * @param remaining     当前剩余额度（撤销后冻结）
 */
public record PermitItemResult(String regionKey, long regionVersion, long validFrom,
                               long validTo, int quota, int remaining) {
}
