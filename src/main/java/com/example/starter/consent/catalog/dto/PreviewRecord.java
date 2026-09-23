package com.example.starter.consent.catalog.dto;

/**
 * 预览中的数据记录项：按记录属性给出唯一目标用途或 UNMAPPED；
 * 属于已撤回/已迁移授权的隔离数据标记为 RETAINED，不写数据。
 *
 * @param subjectKey      主体标识
 * @param epoch           记录所属授权代次
 * @param recordKey       记录键
 * @param attributeValue  记录属性取值，可能为空
 * @param expectedVersion 记录行版本，预览与激活之间被改绑即变化
 * @param isolated        是否为撤回/迁移授权下的隔离数据
 * @param mappingResult   MAPPED / UNMAPPED / RETAINED
 * @param targetPurpose   唯一目标新用途代码；UNMAPPED/RETAINED 时为空
 */
public record PreviewRecord(String subjectKey, int epoch, String recordKey, String attributeValue,
                            long expectedVersion, boolean isolated, MappingResult mappingResult,
                            String targetPurpose) {
}
