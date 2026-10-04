package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 配对锁定请求；expectedVersion 为乐观并发期望版本，
 * 必须等于当前样本链版本（记录总数），否则 409。
 *
 * @param expectedVersion 期望的样本链版本号
 */
public record SampleLockRequest(
        @NotNull(message = "expectedVersion 不能为空")
        @PositiveOrZero(message = "expectedVersion 不能为负数")
        Integer expectedVersion
) {
}