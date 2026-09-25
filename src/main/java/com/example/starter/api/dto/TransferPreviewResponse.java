package com.example.starter.api.dto;

import java.util.List;

/**
 * 容量转配预览结果（只读，不落库）。
 *
 * @param valid      是否存在违规；false 时激活必然失败
 * @param routes     参与航线当前版本（按 routeId 升序）
 * @param buckets    受影响时空桶余量（按 cellId、bucketStart 升序）
 * @param violations 违规明细（按错误码、描述升序）
 */
public record TransferPreviewResponse(boolean valid, List<RouteVersionDto> routes,
                                      List<BucketMarginDto> buckets, List<ViolationDto> violations) {
}
