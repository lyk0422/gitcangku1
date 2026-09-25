package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重量差异复核请求。仅当前保管人可提交；复核不可逆，每件证物至多一次。
 *
 * @param commandKey 幂等命令键
 * @param note       复核说明，写入不可变记录
 */
public record WeightReviewRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String note) {
}
