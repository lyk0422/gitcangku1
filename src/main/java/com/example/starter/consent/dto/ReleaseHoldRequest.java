package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保留冻结解除请求：须由不同于创建人的保留角色提交说明，解除记录不可变。
 *
 * @param requestId  幂等请求标识
 * @param holdKey    待解除的冻结标识
 * @param releasedBy 解除人（保留角色操作人标识），必须不同于创建人
 * @param note       解除说明，写入后不可变
 */
public record ReleaseHoldRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String holdKey,
        @NotBlank @Size(max = 128) String releasedBy,
        @NotBlank @Size(max = 512) String note) {
}
