package com.example.starter.repo;

import java.util.List;

/**
 * 转配航线级冻结证据。
 *
 * @param transferKey           转配单标识
 * @param routeId               航线标识
 * @param fromVersion           转配前版本
 * @param toVersion             转配后版本
 * @param reviewId              激活时的当前 CLEAR 审查依据标识
 * @param reviewRouteVersion    审查依据的航线版本
 * @param reviewAirspaceVersion 审查依据的空域版本
 * @param beforePlan            转配前穿越序列
 * @param afterPlan             转配后穿越序列
 */
public record TransferRouteEvidencePo(String transferKey, String routeId, int fromVersion,
                                      int toVersion, String reviewId, int reviewRouteVersion,
                                      long reviewAirspaceVersion, List<PlanSlotPo> beforePlan,
                                      List<PlanSlotPo> afterPlan) {
}
