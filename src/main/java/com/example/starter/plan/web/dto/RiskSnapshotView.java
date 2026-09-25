package com.example.starter.plan.web.dto;

/**
 * 站台风险快照视图：标记 PLATFORM_RISK 时固化的信息；无风险时为 null。
 *
 * @param scheduleKey              受影响计划业务键
 * @param platformCode             超长停靠站台代码
 * @param trainLength              固化时编组长度，单位米
 * @param platformLengthSnapshot   下调前站台有效长度快照，单位米
 * @param planVersion              固化时计划版本
 */
public record RiskSnapshotView(String scheduleKey, String platformCode, int trainLength,
                               int platformLengthSnapshot, int planVersion) {
}
