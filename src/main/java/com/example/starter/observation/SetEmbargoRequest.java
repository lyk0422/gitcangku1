package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量禁运设置请求：任一簇非法时整次回滚，不写入任何部分结果。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param items     禁运条目集合；服务端按 observationId 排序规范化后判定与落库
 */
public record SetEmbargoRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotEmpty List<@Valid SetEmbargoItem> items) {
}