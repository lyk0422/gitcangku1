package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 污染登记请求。指定一件证物及一个已有接触事件序号作为污染起点；
 * 仅起点证物的当前保管人可登记（越权 403，先于幂等重放判定）。
 *
 * @param commandKey   幂等命令键
 * @param evidenceKey  污染起点证物业务键，须为操作人当前持有
 * @param originEventSeq 污染起点接触事件序号，必须已存在；仅扫描该序号及之后批次
 */
public record ContaminationRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotNull @Positive Long originEventSeq) {
}
