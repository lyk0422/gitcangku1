package com.example.starter.evidence.destruction.dto;

import java.util.List;

/**
 * 创建销毁令整单失败响应体（HTTP 422）：任一件证物不合格则不创建销毁令，
 * 逐件返回不合格证物及原因，顺序与提交列表一致。
 *
 * @param status HTTP 状态码（422）
 * @param message 错误概述
 * @param items  逐件不合格原因
 */
public record DestructionValidationError(
        int status,
        String message,
        List<DestructionItemError> items) {
}
