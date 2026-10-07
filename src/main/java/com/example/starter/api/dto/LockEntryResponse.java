package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本及锁定时冻结的体积。
 *
 * @param sizeBytes 锁定时冻结的制品体积，单位字节，不随后续元数据变化
 */
public record LockEntryResponse(String name, int version, long sizeBytes) {
}
