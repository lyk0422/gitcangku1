package com.example.starter.incident;

import java.time.Instant;

/**
 * 合成残余风险模型实体，对应 incident_risk_models 表（合成数据，不连接真实安全设备）。
 * initialRisk 为初始风险 R（1~1000 整数），residualLimit 为允许残余上限 L（非负且小于 R），
 * 两者首次设置后不可修改；version 为模型乐观锁版本，初始 0，
 * 屏障修改/撤销携带 expectedVersion 匹配成功后加一。时间均为 UTC。
 */
public record RiskModel(
        long id,
        long incidentId,
        int initialRisk,
        int residualLimit,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
