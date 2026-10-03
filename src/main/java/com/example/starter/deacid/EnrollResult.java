package com.example.starter.deacid;

/**
 * 入组结果。
 *
 * @param outcome 结果：ENROLLED=已入组
 * @param batchId 批次主键
 * @param pageId  档案页主键
 */
public record EnrollResult(String outcome, long batchId, long pageId) {
}