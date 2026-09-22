package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本。
 */
public record LockEntryResponse(String name, int version) {
}
