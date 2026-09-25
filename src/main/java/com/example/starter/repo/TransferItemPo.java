package com.example.starter.repo;

/**
 * 容量转配单航线明细（不可变证据）。
 *
 * @param transferKey     所属转配单标识
 * @param routeId         参与航线标识
 * @param expectedVersion 转配前航线版本
 * @param newVersion      转配后航线版本
 * @param sourceCellId    源桶空域单元
 * @param sourceBucket    源桶起点，epoch 秒
 * @param targetCellId    目标桶空域单元
 * @param targetBucket    目标桶起点，epoch 秒
 * @param reviewId        转配时冻结的审查依据记录标识
 * @param beforeCells     转配前穿越序列快照，格式 cell@bucket;...
 * @param afterCells      转配后穿越序列快照
 */
public record TransferItemPo(String transferKey, String routeId, int expectedVersion, int newVersion,
                             String sourceCellId, long sourceBucket,
                             String targetCellId, long targetBucket,
                             String reviewId, String beforeCells, String afterCells) {
}
