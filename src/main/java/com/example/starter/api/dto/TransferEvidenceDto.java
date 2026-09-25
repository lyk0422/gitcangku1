package com.example.starter.api.dto;

import java.util.List;

/**
 * 容量转配冻结证据（只读查询结果，稳定排序）。
 *
 * @param transferKey 转配单标识
 * @param createdAt   激活时间，epoch 毫秒（UTC）
 * @param items       转配项（按请求内顺序号升序）
 * @param routes      逐航线冻结证据（按 routeId 升序）
 * @param buckets     受影响时空桶余量（按 cellId、bucketStart 升序）
 */
public record TransferEvidenceDto(String transferKey, long createdAt,
                                  List<TransferItemDto> items,
                                  List<TransferRouteEvidenceDto> routes,
                                  List<BucketMarginDto> buckets) {
}
