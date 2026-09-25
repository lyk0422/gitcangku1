package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 容量转配单项：在指定航线版本上，把源时空桶的占用交换到目标时空桶。
 *
 * @param routeId         参与转配的航线标识
 * @param routeVersion    要转配占用的航线版本
 * @param expectedVersion 乐观并发期望的当前航线版本（激活时必须等于当前版本）
 * @param source          源时空桶（必须对应该航线当前穿越序列中的一个占用项）
 * @param target          目标时空桶（必须与相邻路径连续且转配后不穿越禁飞区）
 */
public record TransferItemRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Integer expectedVersion,
        @NotNull @Valid BucketRefDto source,
        @NotNull @Valid BucketRefDto target) {
}
