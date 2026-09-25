package com.example.starter.api.dto;

import java.util.List;

/**
 * 容量转配预览结果（只读，不产生任何持久化变更）。
 *
 * @param routes     各参与航线转配前后穿越序列（按 routeId 排序）
 * @param buckets    转配涉及时空桶的前后占用与余量（按单元、桶排序）
 * @param violations 全部违规明细；为空表示可按当前状态激活
 * @param applicable 是否可激活（violations 为空）
 */
public record TransferPreviewResult(List<TransferRoutePreview> routes,
                                    List<BucketUsageDto> buckets,
                                    List<TransferViolationDto> violations,
                                    boolean applicable) {
}
