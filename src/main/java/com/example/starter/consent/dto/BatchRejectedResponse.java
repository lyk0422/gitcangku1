package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批量查询整批拒绝响应体：仅包含批次级稳定错误码与失败项索引／错误码，不含任何记录 payload。
 *
 * @param code     批次级稳定错误码
 * @param failures 全部失败项，含其在输入中的索引与稳定错误码
 */
public record BatchRejectedResponse(String code, List<BatchFailureItem> failures) {
}
