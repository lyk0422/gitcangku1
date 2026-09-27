package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 创建设备能源隔离令请求：创建时一次性固化完整锁定点集合（去重、规范化排序）。
 * 隔离令创建后处于 PENDING，须由两名不同维护角色（ELECTRICIAN/MECHANIC）分别确认全部锁定点后生效。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param orderId    隔离令唯一标识（全局唯一）
 * @param operator   创建操作者（创建时固化）
 * @param lockPointIds 锁定点业务标识集合；不可为空，隔离令内不可重复，创建后不可增删
 */
public record CreateIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String orderId,
        @NotBlank String operator,
        @NotEmpty @Valid List<@NotBlank String> lockPointIds) {
}
