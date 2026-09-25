package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本及锁定时固化的许可证。
 *
 * @param license 锁定时固化的许可证标识，UNKNOWN 表示未登记；后续许可证修订不改写
 */
public record LockEntryResponse(String name, int version, String license) {
}
