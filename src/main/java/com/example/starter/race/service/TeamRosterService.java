package com.example.starter.race.service;

import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RosterResponse;
import com.example.starter.race.api.TeamStandingResponse;
import com.example.starter.race.api.WithdrawMemberRequest;

/**
 * 团队名单锁定应用服务；锁定名单在单个数据库事务内完成
 * “归属/版本校验 + 个人成绩快照固化 + 赛事版本推进 + 幂等记录”原子提交。
 */
public interface TeamRosterService {

    /**
     * 封榜前为团队锁定一个完整名单版本：
     * 成员须已登记、请求内不重复、不得跨队、后续版本只能增员不能替换老成员；
     * 锁定时把成员个人成绩归属固化到该版本。任一成员不合法则整份名单不生成。
     */
    ServiceResult lockRoster(String raceId, String teamId, LockRosterRequest request);

    /**
     * 登记成员退赛（具体生效场次与原因）；不从锁定名单删人、不推进赛事版本，
     * 封榜前后均可登记；已封榜成绩不因此重算。
     */
    ServiceResult withdrawMember(
            String raceId, String teamId, String bib, WithdrawMemberRequest request);

    /** 查询团队当前名单（最新锁定版本合并最新退赛状态）。 */
    RosterResponse getCurrentRoster(String raceId, String teamId);

    /** 查询团队指定历史锁定版本（原样返回，不随退赛或后续版本改写）。 */
    RosterResponse getRosterVersion(String raceId, String teamId, int rosterVersion);

    /**
     * 查询团队成绩榜：OPEN 时按各队当前锁定版本固化成绩实时汇总退赛；
     * SEALED 时返回封榜只读团队快照。
     *
     * @param session 实时计分层场次；退赛生效场次不晚于该值的成员不计分；封榜后该参数被忽略
     */
    TeamStandingResponse getTeamResults(String raceId, Integer session);

    /** 查询封榜团队只读快照；未封榜为404。 */
    TeamStandingResponse getTeamSnapshot(String raceId);
}
