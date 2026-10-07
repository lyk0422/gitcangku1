package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本。
 *
 * @param sizeBytes 锁定时冻结的该制品版本体积，单位字节，非负；不随后续元数据补齐而改变
 */
public record LockEntryResponse(String name, int version, long sizeBytes) {
}
