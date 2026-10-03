package com.example.starter.custody;

import java.time.Instant;

/**
 * 批次移交申请记录。
 *
 * @param id           主键
 * @param batchId      被移交批次ID
 * @param fromHolder   移交方(申请时持有人)
 * @param toHolder     接收方
 * @param status       移交状态
 * @param rejectReason 拒绝原因,仅REJECTED时有值
 * @param decidedAt    签收/拒绝时间,未决定为null
 */
public record TransferRecord(long id, long batchId, String fromHolder, String toHolder,
                             TransferStatus status, String rejectReason, Instant decidedAt) {
}