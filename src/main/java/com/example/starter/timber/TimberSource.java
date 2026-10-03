package com.example.starter.timber;

/**
 * 木材来源批次：一次采伐凭证对应的入库登记。
 *
 * @param id 主键
 * @param voucherNo 采伐凭证编号，全局唯一
 * @param species 树种
 * @param weightKg 入库重量，单位公斤，正整数
 * @param holder 登记时持有人
 */
public record TimberSource(long id, String voucherNo, String species, int weightKg, String holder) {
}