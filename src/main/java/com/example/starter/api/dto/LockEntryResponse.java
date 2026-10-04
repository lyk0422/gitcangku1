package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及其冻结的能力声明。
 *
 * @param capabilities 锁定时该制品版本声明的能力名（升序，冻结不随后续变更）
 */
public record LockEntryResponse(String name, int version, List<String> capabilities) {
}