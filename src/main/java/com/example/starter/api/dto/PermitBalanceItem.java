package com.example.starter.api.dto;

/**
 * 豁免包区域项的只读余额视图。
 *
 * @param regionKey     区域标识
 * @param regionVersion 区域版本
 * @param validFrom     有效起始时间（含），epoch 毫秒（UTC）
 * @param validTo       有效结束时间（不含），epoch 毫秒（UTC）
 * @param quota         签发额度（次）
 * @param remaining     当前剩余额度（次）
 */
public record PermitBalanceItem(String regionKey, long regionVersion, long validFrom,
                                long validTo, int quota, int remaining) {
}
