package com.example.starter.maintenance;

import java.util.List;

/**
 * 批量观测提交响应：整批成功后返回每条观测固化的维护标记；任一明细失败则整批回滚、无此响应。
 *
 * @param requestId    幂等请求标识
 * @param acceptedCount 实际写入条数（等于请求条数）
 * @param affectedCount 其中命中维护窗口的条数
 * @param observations  按请求顺序排列的逐条结果
 */
public record SubmitObservationsResponse(
        String requestId,
        int acceptedCount,
        int affectedCount,
        List<ObservationResultResponse> observations) {
}
