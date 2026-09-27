package com.example.starter.maintenance;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 观测批量提交请求：可混合多个站点，逐条按提交时刻匹配窗口并写入维护标记；
 * 任一观测格式错误或站点引用不存在，整批回滚，不留半成品。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param observations 本批观测明细，不允许为空
 */
public record SubmitObservationsRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotEmpty @Valid List<ObservationItem> observations) {
}
