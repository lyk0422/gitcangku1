package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 漂移修正预览请求：只计算不写数据，返回区间内全部读数的旧值、新值、插值段和受影响保养项目。
 *
 * @param correctionKey  修正单业务标识（预览不占键，仅随响应回显）
 * @param anchors        2~20 个锚点；按读数采样时刻规范化排序，校准值须严格递增
 */
public record DriftCorrectionPreviewRequest(
        @NotBlank String correctionKey,
        @NotNull @Size(min = 2, max = 20) List<@Valid DriftAnchorInput> anchors) {
}
