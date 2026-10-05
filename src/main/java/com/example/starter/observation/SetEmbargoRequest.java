package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量禁运设置请求：先完整校验所有条目，再在单事务内一次性提交；任一条目非法整次回滚。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 * @param items     禁运条目集合，至少一条；同一请求内 observationId 不得重复
 */
public record SetEmbargoRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotEmpty List<@Valid EmbargoItemRequest> items) {
}
