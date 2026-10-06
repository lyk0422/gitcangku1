package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 整箱交接发起请求。仅根箱当前保管人可发起，接收人必须与发起人不同。
 *
 * @param requestId       全局幂等请求键
 * @param toCustodian     指定接收人
 * @param expectedVersion 根箱期望版本（乐观并发控制）
 */
public record ContainerTransferInitiateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String toCustodian,
        @Min(1) int expectedVersion) {
}
