package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单个检查点的分段明细；elapsedMillis 为 null 表示该检查点缺失（漏点）。
 *
 * @param checkpointCode 检查点编码
 * @param seq            检查点顺序，从1开始
 * @param elapsedMillis  分段耗时（毫秒）；缺失为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SplitDetailResponse(String checkpointCode, int seq, Long elapsedMillis) {
}
