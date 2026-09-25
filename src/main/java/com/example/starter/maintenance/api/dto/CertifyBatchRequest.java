package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 批次认证请求：一个批次可认证多台设备的多条读数，全部校验通过才在一个事务内生效，
 * 任一失败整批回滚。certKey 为幂等键：同键同参重放完整重算结果，失败不占键。
 *
 * @param certKey      批次认证幂等键（指纹含认证人、读数版本与规范化批次）
 * @param certifiedBy  认证人标识；不得为任何被认证读数当前版本的录入人（否则 403）
 * @param items        待认证读数列表，非空；规范化时按设备、采样时刻排序
 */
public record CertifyBatchRequest(
        @NotBlank String certKey,
        @NotBlank String certifiedBy,
        @NotEmpty List<@Valid CertifyReadingItem> items) {
}
