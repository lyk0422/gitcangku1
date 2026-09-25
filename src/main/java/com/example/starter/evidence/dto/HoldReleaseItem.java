package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量解除中的单个冻结目标：冻结业务键 + 请求方认定的当前版本（乐观锁）。
 *
 * @param holdId          冻结业务键
 * @param expectedVersion 请求方认定的冻结当前版本；与库内不一致则整批失败回滚
 */
public record HoldReleaseItem(
        @NotBlank @Size(max = 64) String holdId,
        @NotNull Integer expectedVersion) {
}
