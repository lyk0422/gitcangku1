package com.example.starter.evidence.dto;

import com.example.starter.evidence.ChainDirection;

import java.time.LocalDateTime;

/**
 * 双案保管链事件视图。只读查询返回，按发生顺序（id 升序）排列。
 *
 * @param caseKey      该链事件所属案件键
 * @param evidenceKey  证物业务键
 * @param transferId   关联跨案移交批次业务键
 * @param direction    链方向
 * @param orderVersion 事件使用的移交令版本
 * @param actorId      事件操作人
 * @param createdAt    事件时间（UTC）
 */
public record CustodyCaseLinkView(
        String caseKey,
        String evidenceKey,
        String transferId,
        ChainDirection direction,
        String orderVersion,
        String actorId,
        LocalDateTime createdAt) {
}
