package com.example.starter.evidence.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量解除冻结请求。逐项校验请求方与版本，任一失败整批回滚，不产生部分解除。
 *
 * @param commandKey 幂等命令键
 * @param releases   解除目标集合（非空）
 */
public record HoldBatchReleaseRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotEmpty List<@Valid HoldReleaseItem> releases) {
}
