package com.example.starter.plan.model;

/**
 * 站台风险快照：站台长度下调导致未来已发布计划编组超长时，
 * 在同一事务内固化原站台长度并标记 PLATFORM_RISK；整改清除前保持不变。
 *
 * @param planId                   受影响计划 id
 * @param platformCode             超长停靠站台代码
 * @param trainLength              固化时编组长度，单位米
 * @param platformLengthSnapshot   下调前站台有效长度原长度快照，单位米
 * @param planVersion              固化时计划版本
 */
public record RiskSnapshot(long planId, String platformCode, int trainLength,
                           int platformLengthSnapshot, int planVersion) {
}
