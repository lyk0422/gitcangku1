package com.example.starter.repo;

/**
 * 航线版本穿越序列中的一个占用项（与航点序号一一对应）。
 *
 * @param seq         序列序号，从 0 开始
 * @param cellX       占用网格单元 X 索引
 * @param cellY       占用网格单元 Y 索引
 * @param bucketStart 占用时间桶起始时刻，epoch 毫秒（UTC），15 分钟对齐
 */
public record PlanSlotPo(int seq, int cellX, int cellY, long bucketStart) {
}
