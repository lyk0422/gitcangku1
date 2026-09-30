package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 容器巡检确认请求。两名不同保管人以同一 requestId 先后确认同一容器版本；
 * 同 requestId 重放返回首次结果，换封条结果或版本返回 409。
 *
 * @param requestId        巡检请求键（幂等键），全局唯一
 * @param containerVersion 见证的容器版本，须等于容器当前版本
 * @param sealIntact       封条结果：true 完好 / false 异常
 * @param note             巡检备注，可空
 */
public record ContainerInspectionRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long containerVersion,
        @NotNull Boolean sealIntact,
        @Size(max = 512) String note) {
}
