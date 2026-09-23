package com.example.starter.consent.catalog.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 激活时回传的数据记录确认项：必须覆盖预览中全部记录，并回带映射结果。
 *
 * @param subjectKey      主体标识，必须与预览一致
 * @param epoch           记录所属授权代次，必须与预览一致
 * @param recordKey       记录键，必须与预览一致
 * @param expectedVersion 预览给出的记录行版本
 * @param attributeValue  预览时的记录属性取值（可能为空），用于检测属性变化
 * @param mappingResult   回带的映射结果 MAPPED / UNMAPPED / RETAINED
 * @param targetPurpose   MAPPED 时的唯一目标新用途代码；其余为空
 */
public record ActivateRecordItem(
        @NotNull String subjectKey,
        @NotNull Integer epoch,
        @NotNull String recordKey,
        @NotNull Long expectedVersion,
        @Size(max = 256) String attributeValue,
        @NotNull MappingResult mappingResult,
        String targetPurpose) {

    /**
     * 返回用于属性变化比对的值，null 统一为空串语义由服务层处理。
     */
    public String attributeValueForCheck() {
        return attributeValue;
    }
}
