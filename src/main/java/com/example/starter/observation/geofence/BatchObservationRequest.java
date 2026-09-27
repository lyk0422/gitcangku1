package com.example.starter.observation.geofence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 离线观测批量提交请求：可混合站点；任一条目格式错误整批回滚（422），不写部分结果。
 * 批量条数限制 1..100，越界返回 422 并给出实际数量与要求范围。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  提交操作者标识
 * @param items     观测条目列表，1..100 条
 */
public record BatchObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Valid List<ObservationItemRequest> items) {
}
