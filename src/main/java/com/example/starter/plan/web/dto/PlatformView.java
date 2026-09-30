package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 站台当前能力视图。
 *
 * @param platformId      站台业务键
 * @param platformName    站台显示名称
 * @param maxLengthMeters 长度上限，单位米
 * @param powerSystems    兼容供电制式（去重字典序归一化）
 * @param accessible      是否具备无障碍服务能力
 * @param version         当前能力版本，首次登记为 1
 * @param updatedAt       最近能力变更时刻，UTC（能力无变化的重复登记不更新）
 */
public record PlatformView(String platformId, String platformName, int maxLengthMeters,
                           List<String> powerSystems, boolean accessible, int version,
                           Instant updatedAt) {
}
