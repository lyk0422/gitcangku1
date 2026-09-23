package com.example.starter.api.dto;

import java.util.List;

/**
 * 豁免包只读视图（签发/撤销结果与余额查询共用）。
 *
 * @param permitKey     豁免包标识
 * @param routeVersion  绑定的精确航线版本
 * @param permitVersion 豁免包版本（不可变，恒为 1）
 * @param status        ISSUED / REVOKED
 * @param items         区域项与当前余额
 */
public record PermitView(String permitKey, int routeVersion, int permitVersion,
                         String status, List<PermitBalanceItem> items) {
}
