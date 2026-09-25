package com.example.starter.race.domain;

/**
 * 接力队伍的成绩状态：
 * RACING-尚未完赛（仍有棒次未交接）；
 * RANKED-已完成末棒交接且犯规次数少于2次，参与排名；
 * DISQUALIFIED-累计犯规达到2次，自动取消资格（不排名，完赛记录仍保留）。
 */
public enum RelayTeamStatus {
    RACING,
    RANKED,
    DISQUALIFIED
}
