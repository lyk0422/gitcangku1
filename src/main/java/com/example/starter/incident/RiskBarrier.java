package com.example.starter.incident;

import java.time.Instant;

/**
 * 防护屏障实体，对应 incident_risk_barriers 表。
 * 登记时绑定本事件一个 OPEN 处置任务；削减率为 p/q（0&lt;=p&lt;=q，p、q 均不超 1000，q&gt;=1）；
 * 仅当其任务 DONE 且屏障未撤销（revoked=false）时计入残余削减；
 * 每事件至多 5 个未撤销屏障。时间均为 UTC。
 */
public record RiskBarrier(
        long id,
        long modelId,
        String barrierKey,
        String taskKey,
        int reduceP,
        int reduceQ,
        boolean revoked,
        Instant createdAt,
        Instant updatedAt) {
}
