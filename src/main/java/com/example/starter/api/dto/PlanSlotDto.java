package com.example.starter.api.dto;

/**
 * 穿越序列占用项输出结构。
 *
 * @param seq         序列序号（与航点序号一一对应）
 * @param cellX       网格单元 X 索引
 * @param cellY       网格单元 Y 索引
 * @param bucketStart 时间桶起始时刻，epoch 毫秒（UTC）
 */
public record PlanSlotDto(
        Integer seq,
        Integer cellX,
        Integer cellY,
        Long bucketStart) {
}
