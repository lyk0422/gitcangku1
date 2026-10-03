package com.example.starter.web;

/**
 * 花粉批次视图。
 *
 * @param id 主键
 * @param batchNo 花粉批号
 * @param status 批次状态：ACTIVE/DISABLED
 */
public record PollenBatchView(long id, String batchNo, String status) {
}