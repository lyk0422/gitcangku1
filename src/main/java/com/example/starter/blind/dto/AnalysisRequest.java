package com.example.starter.blind.dto;

import java.util.List;

/**
 * 置换分析请求体。人数、版本等校验在服务层以 422 报告，不用 Bean 校验注解。
 * 参与者列表换序视为同参；调用方不提供分组，分组取自库内盲底映射。
 *
 * @param participantIds 引用的 4~8 个不同未退组参与者编号
 * @param version        冻结引用的结果版本，不小于 1
 */
public record AnalysisRequest(
        List<String> participantIds,
        Integer version
) {
}
