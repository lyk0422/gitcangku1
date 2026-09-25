package com.example.starter.api.dto;

/**
 * 跑道关闭窗口结果。
 *
 * @param closureId      关闭窗口唯一标识
 * @param runwayId       所属跑道标识
 * @param runwayVersion  本次登记生效后的跑道版本
 * @param startUtc       关闭开始时刻，epoch 毫秒（UTC），左闭
 * @param endUtc         关闭结束时刻，epoch 毫秒（UTC），右开
 * @param allowEmergency 是否允许紧急例外
 * @param operator       登记操作者标识
 * @param closureKey      幂等键指纹（跑道标识与版本、规范化时段、例外标志、操作者）
 */
public record ClosureResult(String closureId, String runwayId, int runwayVersion,
                            long startUtc, long endUtc, boolean allowEmergency,
                            String operator, String closureKey) {
}
