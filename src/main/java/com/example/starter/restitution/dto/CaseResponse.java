package com.example.starter.restitution.dto;

import java.util.List;

/**
 * 案件写操作结果视图。
 *
 * @param caseId  案件编号
 * @param status  案件状态 OPEN/DECIDED
 * @param version 操作后案件版本
 * @param items   案件藏品清单
 */
public record CaseResponse(String caseId, String status, long version, List<String> items) {
}
