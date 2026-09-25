package com.example.starter.api.dto;

import java.util.List;

/**
 * 转配单航线明细证据（不可变）。
 *
 * @param routeId         参与航线标识
 * @param expectedVersion 转配前航线版本
 * @param newVersion      转配后航线版本
 * @param sourceBucket    源时空桶
 * @param targetBucket    目标时空桶
 * @param reviewId        转配时冻结的审查依据记录标识
 * @param beforeCells     转配前穿越序列（按穿越序号排序）
 * @param afterCells      转配后穿越序列
 */
public record TransferItemEvidence(String routeId, int expectedVersion, int newVersion,
                                   BucketRefDto sourceBucket, BucketRefDto targetBucket,
                                   String reviewId,
                                   List<BucketRefDto> beforeCells,
                                   List<BucketRefDto> afterCells) {
}
