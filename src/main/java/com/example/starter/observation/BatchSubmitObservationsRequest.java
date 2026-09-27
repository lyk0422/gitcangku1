package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 离线观测批量提交请求：可混合多个站点。任一项格式/语义错误或合并失败整批回滚，
 * 不产生任何提交记录与观测版本（多对象写入先校验完整最终状态，再在一个事务中提交）。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  提交操作者标识
 * @param items     提交项集合，按列表顺序逐项裁决，不允许为空
 */
public record BatchSubmitObservationsRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotEmpty @Valid List<SubmissionItemRequest> items) {
}
