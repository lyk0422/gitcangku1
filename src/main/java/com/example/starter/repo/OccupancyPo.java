package com.example.starter.repo;

/**
 * 航线版本时空桶占用记录（穿越序列的一项）。
 *
 * @param routeId      航线标识
 * @param routeVersion 占用所属航线版本
 * @param seq          穿越序列序号（从 0 连续编号）
 * @param cellId       占用的空域单元标识
 * @param bucketStart  占用的 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC）
 * @param reviewId     激活所依据的审查通过记录标识
 */
public record OccupancyPo(String routeId, int routeVersion, int seq,
                          String cellId, long bucketStart, String reviewId) {
}
