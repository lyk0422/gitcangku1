package com.example.starter.api.dto;

import java.util.List;

/**
 * 预览中某条参与航线的转配前后穿越序列。
 *
 * @param routeId        航线标识
 * @param currentVersion 当前航线版本；航线不存在时为 null
 * @param beforeCells    转配前穿越序列（按穿越序号排序）
 * @param afterCells     转配后穿越序列（源桶位置替换为目标桶）
 */
public record TransferRoutePreview(String routeId, Integer currentVersion,
                                   List<BucketRefDto> beforeCells,
                                   List<BucketRefDto> afterCells) {
}
