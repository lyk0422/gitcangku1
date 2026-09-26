package com.example.starter.evidence.dto;

/**
 * 双案封存快照视图。字段、排序与 null 语义稳定：fromLocation 为 null 表示移交前未登记位置。
 *
 * @param evidenceKey   证物业务键
 * @param sourceCaseKey 封存时来源案件键
 * @param targetCaseKey 封存时目标案件键
 * @param fromLocation  原位置；null 表示移交前未登记
 * @param sealVersion   封存时封签版本快照
 * @param orderVersion  封存时移交令版本
 */
public record CaseTransferItemView(
        String evidenceKey,
        String sourceCaseKey,
        String targetCaseKey,
        String fromLocation,
        int sealVersion,
        String orderVersion) {
}
