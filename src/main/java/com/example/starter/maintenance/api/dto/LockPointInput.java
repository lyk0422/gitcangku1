package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 锁定点输入。label 可空（null 表示调用方未提供名称），空值语义保持为 null。
 *
 * @param lockPointId  锁定点业务标识，同一隔离令内不可重复
 * @param label        锁定点名称，可空
 */
public record LockPointInput(
        @NotBlank String lockPointId,
        String label) {
}
