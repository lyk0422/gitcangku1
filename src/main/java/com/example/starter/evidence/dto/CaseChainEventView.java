package com.example.starter.evidence.dto;

import com.example.starter.evidence.ChainEventType;

import java.time.LocalDateTime;

/**
 * 案件保管链事件视图（只追加、不可变）。
 *
 * @param transferId    所属跨案移交业务 ID
 * @param evidenceKey   证物业务键
 * @param caseKey       事件所属案件
 * @param eventType     链事件：CASE_OUT / CASE_IN / REVERSE_OUT / REVERSE_IN
 * @param seqInTransfer 该证物在批次中的规范化序号
 * @param custodianId   该环节保管人
 * @param location      事件时存放位置；null 表示未登记库位
 * @param sealVersion   事件时封签版本
 * @param orderVersion  依据的移交令版本
 * @param createdAt     链事件发生时刻（UTC）
 */
public record CaseChainEventView(
        String transferId,
        String evidenceKey,
        String caseKey,
        ChainEventType eventType,
        int seqInTransfer,
        String custodianId,
        String location,
        int sealVersion,
        int orderVersion,
        LocalDateTime createdAt) {
}
