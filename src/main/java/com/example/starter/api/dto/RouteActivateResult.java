package com.example.starter.api.dto;

import java.util.List;

/**
 * 航线版本激活结果。
 *
 * @param routeId   航线标识
 * @param version   被激活的航线版本
 * @param reviewId  激活依据的审查记录标识
 * @param occupancy 生成的时空桶占用（按穿越序号排序）
 */
public record RouteActivateResult(String routeId, int version, String reviewId,
                                  List<BucketRefDto> occupancy) {
}
