package com.example.starter.api.dto;

import java.util.List;

/**
 * 容量转配单证据（激活成功后不可变；查询只读）。
 *
 * @param transferKey 转配单业务唯一标识
 * @param appliedAt   激活时间，epoch 毫秒（UTC）
 * @param items       航线明细（按 routeId 稳定排序）
 * @param buckets     涉及时空桶前后占用冻结（按单元、桶稳定排序）
 */
public record TransferEvidenceDto(String transferKey, long appliedAt,
                                  List<TransferItemEvidence> items,
                                  List<BucketUsageDto> buckets) {
}
