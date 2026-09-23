package com.example.starter.api.dto;

import java.util.List;

/**
 * 豁免包签发/撤销/查询结果。
 *
 * @param permitKey    豁免包标识
 * @param routeId      绑定航线标识
 * @param routeVersion 绑定的精确航线版本
 * @param status       ACTIVE / REVOKED
 * @param version      豁免包版本：签发为 1，撤销为 2
 * @param items        区域项（含当前剩余额度）
 */
public record PermitResult(String permitKey, String routeId, int routeVersion,
                           String status, int version, List<PermitItemResult> items) {
}
