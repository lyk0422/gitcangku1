package com.example.starter.evidence.destruction.dto;

/**
 * 入列校验失败的逐件原因。整单 422 时按提交原序返回每件不合格证物的原因。
 *
 * @param evidenceKey 不合格证物业务键
 * @param reason      不合格原因
 */
public record DestructionItemError(
        String evidenceKey,
        String reason) {
}
