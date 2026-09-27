package com.example.starter.firmware.domain;

/**
 * 发布单某豁免版本中的一条模块豁免。
 *
 * @param id            豁免条目ID
 * @param releaseId     所属发布单ID
 * @param version       豁免版本号，同一发布单从1开始，每次整体修改成功加一
 * @param deviceGroup   被豁免设备组，组名完全相等才适用
 * @param moduleName    被豁免模块，逐模块显式列出
 * @param expiresAtUtc  到期时刻，UTC，ISO-8601；左闭右开，到达此时刻自动失效
 * @param createdAtUtc  本版本登记时刻，UTC，ISO-8601
 */
public record ModuleExemption(long id, long releaseId, int version, String deviceGroup, String moduleName,
                              String expiresAtUtc, String createdAtUtc) {
}
