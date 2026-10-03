package com.example.starter.custody;

/**
 * 木材批次记录。
 *
 * @param id        主键
 * @param batchCode 批次编号,全局唯一
 * @param voucherNo 采伐凭证编号,自来源批次继承
 * @param species   树种,自来源批次继承
 * @param weightKg  重量,单位公斤,正整数
 * @param parentId  父批次ID,来源批次为null
 * @param holder    当前持有人
 * @param status    批次状态
 */
public record BatchRecord(long id, String batchCode, String voucherNo, String species,
                          int weightKg, Long parentId, String holder, BatchStatus status) {
}