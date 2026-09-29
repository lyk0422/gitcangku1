package com.example.starter.consent;

/**
 * 受限查询失败原因码：用于 403/422 响应中逐对象或整单的稳定原因定位。
 *
 * <ul>
 *   <li>{@link #PURPOSE_NOT_DECLARED} 用途未在该 epoch 声明查询额度（403）</li>
 *   <li>{@link #QUOTA_INSUFFICIENT} 剩余额度不足以覆盖本次对象数（403）</li>
 *   <li>{@link #OBJECT_CROSS_EPOCH} 对象不属于当前有效 epoch（422）</li>
 *   <li>{@link #OBJECT_NOT_FOUND} 当前 epoch 下对象不存在（422）</li>
 *   <li>{@link #QUANTITY_MISMATCH} 声明数量与去重后对象集合大小不一致（422）</li>
 *   <li>{@link #DUPLICATE_OBJECT} 请求对象键重复，集合无法规范化（422）</li>
 * </ul>
 */
public enum QueryFailureCode {
    PURPOSE_NOT_DECLARED,
    QUOTA_INSUFFICIENT,
    OBJECT_CROSS_EPOCH,
    OBJECT_NOT_FOUND,
    QUANTITY_MISMATCH,
    DUPLICATE_OBJECT
}
