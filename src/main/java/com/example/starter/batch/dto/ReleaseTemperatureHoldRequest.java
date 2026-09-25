package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 解除温控冻结请求。须由质量角色（X-Approval-Role: QUALITY）且不同于任一运输录入人的
 * 操作人提交调查说明；dispositions 必须逐一覆盖当前全部 EXCURSION 运输段，
 * 任一段未处置整次 422 且不解除。
 */
public record ReleaseTemperatureHoldRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "investigationNote 不能为空") String investigationNote,
        @NotNull(message = "dispositions 不能为空")
        @Size(min = 1, message = "dispositions 至少包含一条异常段处置")
        List<@Valid SegmentDisposition> dispositions
) {

    /**
     * 单段处置：segmentKey 必须为当前 EXCURSION 运输段，disposition 为处置说明。
     */
    public record SegmentDisposition(
            @NotBlank(message = "segmentKey 不能为空") String segmentKey,
            @NotBlank(message = "disposition 不能为空") String disposition
    ) {
    }
}
