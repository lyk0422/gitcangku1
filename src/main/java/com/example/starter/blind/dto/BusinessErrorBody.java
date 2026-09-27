package com.example.starter.blind.dto;

import java.util.List;

/**
 * 业务规则错误响应体：含可区分的违规明细列表，便于调用方定位整批中每一条失败原因。
 *
 * @param error      HTTP 状态描述
 * @param message    汇总提示（不含盲底）
 * @param violations 违规明细，至少一条；批次操作任一失败则整次回滚并全部列出
 */
public record BusinessErrorBody(
        String error,
        String message,
        List<ViolationView> violations
) {
}
