package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 航班动作请求：起飞或取消。
 *
 * <p>起飞仅允许 APPROVED 航班；取消允许 PENDING/APPROVED/RUNWAY_RISK 航班，
 * DEPARTED/CANCELLED 为终态。关闭变更、审查、起飞与改航按事务提交顺序裁决。</p>
 *
 * @param flightId  航班唯一标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record FlightActionRequest(
        @NotBlank @Size(max = 64) String flightId,
        @NotBlank @Size(max = 64) String requestId) {
}
