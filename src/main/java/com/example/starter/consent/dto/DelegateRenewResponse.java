package com.example.starter.consent.dto;

import java.util.List;

/**
 * 批量续签响应：逐条返回旧委托指纹、新委托指纹与新版本。
 *
 * @param items 续签结果列表
 */
public record DelegateRenewResponse(List<RenewedItem> items) {

    /**
     * 单条续签结果。
     *
     * @param oldDelegateKey  旧委托指纹（已随续签撤销）
     * @param newDelegateKey  新委托指纹
     * @param delegateVersion 新委托版本（旧版本 + 1）
     */
    public record RenewedItem(String oldDelegateKey, String newDelegateKey, int delegateVersion) {
    }
}
