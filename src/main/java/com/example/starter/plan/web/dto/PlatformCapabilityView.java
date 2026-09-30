package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 站台能力视图（某个版本）。
 *
 * @param platformId          站台 ID
 * @param version             能力版本，发布占用时快照该版本
 * @param maxLengthM          站台可容纳编组长度上限，单位米
 * @param supportedPowerTypes 站台兼容供电制式代码列表
 * @param accessible          站台是否具备无障碍登乘能力
 */
public record PlatformCapabilityView(String platformId, int version, int maxLengthM,
                                     List<String> supportedPowerTypes, boolean accessible) {
}
