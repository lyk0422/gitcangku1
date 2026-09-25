package com.example.starter.repo;

/**
 * 容量转配项记录（不可变）。
 *
 * @param transferKey     所属转配单标识
 * @param seq             项在请求中的顺序号
 * @param routeId         参与航线标识
 * @param expectedVersion 激活时校验的航线版本
 * @param sourceCell      源桶空域单元标识
 * @param sourceBucket    源桶时间桶起始，epoch 毫秒（UTC）
 * @param targetCell      目标桶空域单元标识
 * @param targetBucket    目标桶时间桶起始，epoch 毫秒（UTC）
 */
public record TransferItemPo(String transferKey, int seq, String routeId, int expectedVersion,
                             String sourceCell, long sourceBucket,
                             String targetCell, long targetBucket) {
}
