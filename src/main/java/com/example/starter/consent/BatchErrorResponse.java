package com.example.starter.consent;

import java.util.List;

/**
 * 批量拒绝错误响应体：code 为稳定业务码，reasons 逐对象列出拒绝原因。
 *
 * @param code    稳定业务码
 * @param message 可读描述
 * @param reasons 逐对象拒绝原因列表
 */
public record BatchErrorResponse(String code, String message, List<RejectionReason> reasons) {
}
