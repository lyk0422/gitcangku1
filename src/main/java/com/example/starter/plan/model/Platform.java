package com.example.starter.plan.model;

import java.util.List;

/**
 * 站台当前能力。能力由站台管理方登记，任一属性变更都会产生新版本，历史不可变。
 *
 * @param platformId      站台业务键，全局唯一
 * @param platformName    站台显示名称
 * @param maxLengthMeters 可接纳编组长度上限，单位米；编组长度等于上限视为适配
 * @param powerSystems    兼容供电制式集合（归一化去重字典序）
 * @param accessible      是否具备无障碍服务能力，TRUE 具备
 * @param version         当前能力版本，首次登记为 1
 * @param createdAt       首次登记时刻，UTC 毫秒
 * @param updatedAt       最近能力变更时刻，UTC 毫秒；能力无变化的重复登记不更新
 */
public record Platform(String platformId, String platformName, int maxLengthMeters,
                       List<String> powerSystems, boolean accessible, int version,
                       long createdAt, long updatedAt) {
}
