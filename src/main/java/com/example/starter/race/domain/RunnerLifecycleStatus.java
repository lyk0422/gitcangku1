package com.example.starter.race.domain;

/**
 * 选手赛程生命周期状态（runner_lifecycle 行）：
 * REGISTERED-已登记尚未起跑（缺失生命周期行也按此处理）；
 * STARTED-已成功起跑；
 * WITHDRAWN-已退赛（终态，起跑与检录均被拒绝）。
 *
 * <p>取消资格（DISQUALIFIED）不写入本表：由存在生效 DISQUALIFY 处罚派生，
 * 与退赛/完赛一样视为“已结束”，释放器材绑定。
 */
public enum RunnerLifecycleStatus {
    REGISTERED,
    STARTED,
    WITHDRAWN
}
