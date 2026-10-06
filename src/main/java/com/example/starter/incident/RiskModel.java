package com.example.starter.incident;

import java.time.Instant;

/**
 * 残余风险模型实体，对应 incident_risk_models 表，每事件至多一条。
 * initialRisk 为初始风险 R（1~1000 整数）；residualLimit 为允许残余上限 L（非负且小于 R）；
 * version 为模型版本：初始化置 1，屏障登记/修改/撤销每次成功加一，
 * 修改与撤销请求须携带期望版本（expectedVersion）且等于当前版本；
 * containedAt/frozenResidualNum/frozenResidualDen 为遏制冻结结果（遏制 UTC 时刻与
 * 冻结残余的精确分数分子/分母），仅进入 CONTAINED 时写入，之后的合法加强不回改该依据。
 * 时间均为 UTC。
 */
public record RiskModel(
        long id,
        long incidentId,
        int initialRisk,
        int residualLimit,
        long version,
        Instant containedAt,
        Long frozenResidualNum,
        Long frozenResidualDen,
        Instant createdAt,
        Instant updatedAt) {
}
