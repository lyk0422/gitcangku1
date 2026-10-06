package com.example.starter.repo;

import java.util.List;

/**
 * 航线版本能量档案记录（按航线版本冻结，不可变）。
 *
 * @param routeId             航线标识
 * @param routeVersion        档案绑定的航线版本
 * @param availableEnergy     出发可用能量，非负
 * @param reserveEnergy       必留余量，非负
 * @param segmentConsumptions 每段正整数耗能，条数等于坐标段数（航点数 - 1），最多 10 段
 * @param diversionEnergies   每个航点备降选项的非负整数额外耗能，条数等于航点数
 * @param operator            登记操作者
 * @param requestId           登记写操作请求标识
 * @param createdAt           登记时间（epoch 毫秒）
 */
public record EnergyProfilePo(String routeId, int routeVersion, long availableEnergy,
                              long reserveEnergy, List<Long> segmentConsumptions,
                              List<Long> diversionEnergies, String operator,
                              String requestId, long createdAt) {
}
