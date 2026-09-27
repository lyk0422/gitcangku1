package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量离线观测提交请求：可混合多个站点；任一条目格式错误整批回滚，不留下部分结果。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  提交操作者标识
 * @param items     提交条目（按提交顺序处理，1~50 条）
 */
public record BatchSubmissionRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 50) List<@Valid SubmissionItem> items) {
}
