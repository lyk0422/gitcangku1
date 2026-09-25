package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 发布锁定图请求：将通过来源策略校验的锁定图固化为发布快照。
 *
 * <p>操作者由 X-Operator 请求头提供，参与 provenanceKey 指纹计算。
 */
public record PublishLockRequest(
        @NotNull @Positive Long lockFileId) {
}
