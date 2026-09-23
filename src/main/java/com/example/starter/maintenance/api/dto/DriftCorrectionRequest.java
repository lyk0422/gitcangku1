package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 时钟漂移修正单请求（预览/激活共用）：2～20 个锚点，允许乱序提交，服务端按采样时刻规范化。
 *
 * @param requestId        全局唯一请求标识（幂等键）；同键同参重放首次快照，异参 409，失败不占键
 * @param correctionKey    修正单业务唯一键，全局唯一；激活成功后作为证据查询标识
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param anchors          2～20 个锚点；按采样时刻排序后相邻锚点校准工时严格递增
 */
public record DriftCorrectionRequest(
        @NotBlank String requestId,
        @NotBlank String correctionKey,
        @NotNull Long expectedVersion,
        @NotNull @Size(min = 2, max = 20) @Valid List<DriftAnchorRequest> anchors) {
}
