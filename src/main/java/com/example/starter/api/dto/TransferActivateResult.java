package com.example.starter.api.dto;

import java.util.List;

/**
 * 转配激活成功结果（一次性替换全部占用、逐航线增版后的快照）。
 *
 * @param transferKey 转配单唯一标识
 * @param routes      参与航线的版本与转配后穿越序列
 * @param buckets     受影响桶的转配前后全量占用与容量
 */
public record TransferActivateResult(
        String transferKey,
        List<RouteVersionAfterDto> routes,
        List<BucketCapacityDto> buckets) {
}
