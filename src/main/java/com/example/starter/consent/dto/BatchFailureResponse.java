package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批量查询失败响应：整批拒绝时返回全部失败项索引及稳定错误码，不含 payload。
 *
 * @param failures 失败项列表（按下标升序）
 */
public record BatchFailureResponse(List<BatchItemFailure> failures) {
}
