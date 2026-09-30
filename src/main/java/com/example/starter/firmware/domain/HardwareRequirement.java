package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 固件版本硬件兼容要求：版本声明允许型号集合、最低内存与必需能力标签。
 * 要求一经声明只增不改，重复声明同一版本冲突。
 *
 * @param id              硬件要求ID
 * @param firmwareVersion 固件目标版本号
 * @param minMemoryMb     最低运行内存，单位MB（1GB=1024MB）；null 表示不限制内存
 * @param models          允许投放的设备型号集合，画像型号须命中其一
 * @param tags            必需能力标签集合，画像须包含全部；空集合表示不要求标签
 */
public record HardwareRequirement(long id, String firmwareVersion, Integer minMemoryMb,
                                  List<String> models, List<String> tags) {
}
