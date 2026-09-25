package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批量续签响应：整批生效后的委托列表（新版本）。
 *
 * @param renewed 续签后的委托列表，按委托键排序
 */
public record DelegateRenewResponse(List<DelegateResponse> renewed) {
}
