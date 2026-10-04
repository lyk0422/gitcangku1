package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及其锁定时冻结的能力名。
 */
public record LockEntryResponse(String name, int version, List<String> capabilities) {
}