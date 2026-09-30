package com.example.starter.firmware.domain;

import java.util.Set;

/**
 * 固件版本兼容声明：型号集合、最低内存与必需能力标签。
 *
 * @param id           固件版本ID
 * @param name         版本名，全局唯一
 * @param models       兼容型号集合，至少一个
 * @param minMemoryMb  最低内存要求，单位MB，取值>=0
 * @param requiredTags 必需能力标签，空集合表示无要求
 */
public record FirmwareVersion(long id, String name, Set<String> models, int minMemoryMb,
                              Set<String> requiredTags) {
}
