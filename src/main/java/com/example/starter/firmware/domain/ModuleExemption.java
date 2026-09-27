package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 发布单设备组模块豁免当前态。豁免仅对列出的模块生效，到期时刻后自动失效（UTC 左闭右开）。
 *
 * @param id           豁免ID
 * @param releaseId    所属发布单ID
 * @param groupName    豁免设备组
 * @param version      豁免版本号，从 1 开始，每次修改加一
 * @param modules      显式豁免模块名（按模块名排序），仅这些模块可豁免
 * @param expiresAtUtc 到期时刻，UTC，ISO-8601；当前时刻 &gt;= 该值即失效
 * @param createdAtUtc 当前版本创建时刻，UTC，ISO-8601
 */
public record ModuleExemption(long id, long releaseId, String groupName, int version,
                              List<String> modules, String expiresAtUtc, String createdAtUtc) {
}
