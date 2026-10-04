package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量禁运设置请求：先完整校验全部簇的最终状态，任一非法则整次回滚，再在一个事务中提交。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param items     禁运设置项列表，同一批次内 observationId 不得重复
 */
public record SetEmbargoBatchRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotEmpty List<@Valid EmbargoItemRequest> items) {
}