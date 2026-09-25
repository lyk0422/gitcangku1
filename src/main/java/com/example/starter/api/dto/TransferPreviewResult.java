package com.example.starter.api.dto;

import java.util.List;

/**
 * 转配预览结果（只读）：按完整后态计算各航线穿越序列与全部桶占用，
 * 返回版本、容量余量（含未参与航线占用）和违规明细。
 *
 * @param transferKey     转配单标识（预览可缺省为空串）
 * @param airspaceVersion 预览时读取到的当前全局空域版本
 * @param feasible        完整后态是否可行（无任何违规）
 * @param routes          参与航线预览
 * @param buckets         受影响时空桶的容量余量
 * @param violations      全部违规明细（桶级与航线级）
 */
public record TransferPreviewResult(
        String transferKey,
        Long airspaceVersion,
        boolean feasible,
        List<RoutePreviewDto> routes,
        List<BucketCapacityDto> buckets,
        List<ViolationDto> violations) {
}
