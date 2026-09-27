package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 案件保管链事件实体，对应 case_custody_chain_event 表。记录只追加、不可变；
 * 跨案移交同事务为每件证物写来源案移出（CASE_OUT）与目标案移入（CASE_IN）两条；
 * 撤销追加反向移出/移入两条，归属切换不改写旧事件。
 *
 * @param id           主键
 * @param transferId   所属跨案移交业务 ID
 * @param evidenceKey  关联证物业务键
 * @param caseKey      事件所属案件
 * @param eventType    链事件类型
 * @param seqInTransfer 该证物在批次中的规范化序号（从 0 开始）
 * @param custodianId  该环节保管人
 * @param location     事件时存放位置快照；null 表示未登记库位
 * @param sealVersion  事件时封签版本快照
 * @param orderVersion 依据的移交令版本快照
 * @param createdAt    链事件发生时刻（UTC）
 */
public record CaseChainEvent(
        Long id,
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
