package com.example.starter.timber;

/**
 * 木材批次树节点：来源根批次或拆分出的子批次。
 *
 * @param id 主键
 * @param batchNo 批次编号，全局唯一
 * @param sourceId 所属来源ID
 * @param parentId 父批次ID，NULL表示来源根批次
 * @param voucherNo 继承来源的采伐凭证编号
 * @param species 继承来源的树种
 * @param weightKg 当前重量，单位公斤，正整数
 * @param holder 当前持有人
 * @param leaf 是否叶子批次：true未拆分可移交或再拆分，false已拆分仅作来源节点
 */
public record TimberBatch(long id, String batchNo, long sourceId, Long parentId,
                          String voucherNo, String species, int weightKg, String holder, boolean leaf) {
}