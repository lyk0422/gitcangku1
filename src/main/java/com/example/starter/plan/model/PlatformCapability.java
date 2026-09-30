package com.example.starter.plan.model;

import java.util.Set;

/**
 * 站台能力的一个不可变版本。历史版本长期保留，发布登记时快照所用版本。
 *
 * @param platformId         站台 ID
 * @param version            能力版本，从 1 开始递增
 * @param maxLengthM         站台可容纳编组长度上限，单位米
 * @param supportedPowerTypes 站台兼容的供电制式集合（已按逗号串解析）
 * @param accessible         站台是否具备无障碍登乘能力
 */
public record PlatformCapability(String platformId, int version, int maxLengthM,
                                 Set<PowerSupplyType> supportedPowerTypes, boolean accessible) {
}
