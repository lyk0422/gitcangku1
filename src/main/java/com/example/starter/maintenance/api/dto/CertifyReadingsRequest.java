package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 批量认证读数请求。一个批次可跨设备认证多条读数：
 * 先按设备与读数采样时刻排序验证最终已认证序列单调不减，任一倒退或校验失败整批回滚。
 *
 * @param requestId  全局唯一请求标识（certKey 幂等键）
 * @param certifier  认证人标识；必须不同于每条读数当前修订版本的录入人，否则 403
 * @param items      待认证读数列表，至少一条；批次内同一读数不可重复
 */
public record CertifyReadingsRequest(
        @NotBlank String requestId,
        @NotBlank String certifier,
        @NotEmpty List<@Valid Item> items) {

    /**
     * 待认证读数条目。
     *
     * @param equipmentId        设备标识
     * @param readingId          读数标识
     * @param expectedRevisionNo 期望认证的读数修订号，与当前修订号不一致时返回 422
     */
    public record Item(
            @NotBlank String equipmentId,
            @NotBlank String readingId,
            @NotNull @Positive Integer expectedRevisionNo) {
    }
}
