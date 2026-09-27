package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保养更正附录创建请求：仅允许对已完成保养追加附录，不能重开周期或覆盖工时历史。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param content    更正内容（实际值、要求值或差额等说明）
 * @param createdBy  附录创建人
 */
public record CorrectionCreateRequest(
        @NotBlank String requestId,
        @NotBlank @Size(max = 2000) String content,
        @NotBlank String createdBy) {
}
