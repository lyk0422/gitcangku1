package com.example.starter.firmware.domain;

/**
 * 模块间依赖边：moduleName 依赖 dependsOn，随模块一并纳入固件完整依赖集合。
 *
 * @param moduleName 依赖方模块名
 * @param dependsOn  被依赖模块名，与 moduleName 相同即自依赖，按环处理
 * @param minVersion 被依赖模块最低版本，点分数字，无版本要求登记为 0
 * @param updatedAtUtc 最近登记时刻，UTC，ISO-8601
 */
public record ModuleDependency(String moduleName, String dependsOn, String minVersion, String updatedAtUtc) {
}
