package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 装箱/拆箱请求。expectedVersion 为目标箱所属根箱的当前版本，校验通过后根箱版本加一。
 * evidenceKeys 与 containerKeys 至少一项非空。
 *
 * @param requestId       全局幂等请求键
 * @param expectedVersion 根箱期望版本（乐观并发控制）
 * @param evidenceKeys    待装箱/拆箱的证物键列表
 * @param containerKeys   待装箱/拆箱的子箱键列表
 */
public record ContainerMembersRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) int expectedVersion,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 64) String> evidenceKeys,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 64) String> containerKeys) {
}
