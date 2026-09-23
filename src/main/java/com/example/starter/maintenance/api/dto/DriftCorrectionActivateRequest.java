package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 漂移修正激活请求：在一个事务内重读锚点与区间读数、校验、生成新修订并原子重算保养快照。
 *
 * @param requestId        全局唯一请求标识（幂等键）；锚点换序按时间规范化后视为同参
 * @param correctionKey    修正单业务标识，全局唯一；已被占用返回 409
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param anchors          2~20 个锚点；按读数采样时刻规范化排序，校准值须严格递增
 */
public record DriftCorrectionActivateRequest(
        @NotBlank String requestId,
        @NotBlank String correctionKey,
        @NotNull Long expectedVersion,
        @NotNull @Size(min = 2, max = 20) List<@Valid DriftAnchorInput> anchors) {
}
