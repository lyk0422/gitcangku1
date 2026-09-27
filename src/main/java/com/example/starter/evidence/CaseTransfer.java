package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 跨案移交主单实体，对应 case_transfer 表。撤销只改本单状态并追加反向链，不删除原单。
 *
 * @param id                   主键
 * @param transferId           跨案移交业务 ID（UUID），双案快照与链事件共享
 * @param orderNo              移交令编号
 * @param orderVersion         移交令版本（快照固化）
 * @param fromCaseKey          来源案件键
 * @param toCaseKey            目标案件键
 * @param fromCustodian        来源案件保管人
 * @param toCustodian          目标案件保管人
 * @param status               移交状态
 * @param createdAt            移交完成时刻（UTC）
 * @param revokedAt            撤销时刻（UTC）；null 表示未撤销
 * @param revokeFromCustodian  撤销时确认的来源案件保管人；null 表示未撤销
 * @param revokeToCustodian    撤销时确认的目标案件保管人；null 表示未撤销
 */
public record CaseTransfer(
        Long id,
        String transferId,
        String orderNo,
        int orderVersion,
        String fromCaseKey,
        String toCaseKey,
        String fromCustodian,
        String toCustodian,
        CaseTransferStatus status,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        String revokeFromCustodian,
        String revokeToCustodian) {
}
