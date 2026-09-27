package com.example.starter.observation;

import java.util.List;

/**
 * 批量离线合并响应：按请求项顺序返回每条观测记录的合并结果。
 *
 * @param results 各项合并后的观测记录响应（与请求项一一对应）
 */
public record BatchMergeResponse(
        List<ObservationResponse> results) {
}
