package com.example.starter.repo;

/**
 * 容量占用记录：ACTIVE 航线版本在某时空桶占用 1 架次。
 *
 * @param routeId      占用航线标识
 * @param routeVersion 占用航线版本
 * @param cellId       空域单元标识
 * @param bucketStart  15 分钟 UTC 时间桶起点，epoch 秒
 * @param seq          对应穿越序列中的序号（从 0 开始）
 */
public record OccupancyPo(String routeId, int routeVersion, String cellId,
                          long bucketStart, int seq) {
}
