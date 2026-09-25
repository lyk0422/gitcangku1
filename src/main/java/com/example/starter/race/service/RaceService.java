package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AddTeamMemberRequest;
import com.example.starter.race.api.BatchLockRosterRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RemoveTeamMemberRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTeamResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TeamRosterResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.api.UnlockRosterRequest;

/**
 * 赛事成绩封榜应用服务；每个写方法在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交。
 */
public interface RaceService {

    /** 新建赛事（初始版本1、OPEN）。 */
    ServiceResult createRace(CreateRaceRequest request);

    /** 登记选手；版本不匹配或赛事已封榜返回409。 */
    ServiceResult registerRunner(String raceId, RegisterRunnerRequest request);

    /** 修订原始完赛耗时。 */
    ServiceResult reviseTime(String raceId, ReviseTimeRequest request);

    /** 新增处罚（加时或取消资格）。 */
    ServiceResult addPenalty(String raceId, AddPenaltyRequest request);

    /** 撤销处罚。 */
    ServiceResult revokePenalty(String raceId, String penaltyId, RevokePenaltyRequest request);

    /**
     * 一次性配置检查点（1~20个、代码赛事内唯一、顺序从1连续递增）；
     * 仅 OPEN 且尚无任何分段记录时可配置，成功后版本加一且不可修改。
     */
    ServiceResult configureCheckpoints(String raceId, ConfigureCheckpointsRequest request);

    /**
     * 提交选手检查点通过记录；乱序允许，但相邻检查点耗时须严格递增，
     * 且 elapsedMillis 必须小于该选手原始完赛耗时；违反返回422且不写入。
     */
    ServiceResult submitTiming(String raceId, String bib, SubmitTimingRequest request);

    /** 封榜：校验版本并原子保存只读成绩快照（含每名选手分段明细与缺失检查点）。 */
    ServiceResult sealRace(String raceId, SealRaceRequest request);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);

    /** 创建队伍（队长须为已报名选手）；版本不匹配或赛事已封榜返回409。 */
    ServiceResult createTeam(String raceId, CreateTeamRequest request);

    /** 新增队伍成员；仅名单未锁定时可用，每名参赛者同一赛事最多属于一支队伍。 */
    ServiceResult addTeamMember(String raceId, String teamId, AddTeamMemberRequest request);

    /** 移除队伍成员；仅名单未锁定时可用。 */
    ServiceResult removeTeamMember(String raceId, String teamId, String bib,
                                   RemoveTeamMemberRequest request);

    /**
     * 队长提交名单锁定：人数须为2~8且全部成员具有有效个人报名，否则422；
     * 锁定后禁止普通增删成员。幂等键为 rosterKey 指纹（队长+赛事版本+队伍+规范化成员集合），
     * 同键同参重放首次结果，失败不占键。
     */
    ServiceResult lockRoster(String raceId, String teamId, LockRosterRequest request);

    /**
     * 批量锁定多支队伍：先整批校验成员不跨队、人数与全部个人报名，任一失败整批422；
     * 成功后在一事务写入所有锁定快照。
     */
    ServiceResult batchLockRosters(String raceId, BatchLockRosterRequest request);

    /** 裁判解锁队伍名单（须说明原因）；不删除旧锁定快照，重锁生成新版本；封榜后409。 */
    ServiceResult unlockRoster(String raceId, String teamId, UnlockRosterRequest request);

    /** 查询队伍名单：当前状态、名单版本与全部锁定历史；只读。 */
    TeamRosterResponse getTeamRoster(String raceId, String teamId);

    /** 查询参赛者队伍归属；未加入任何队伍时 teamId 为 null；只读。 */
    RunnerTeamResponse getRunnerTeam(String raceId, String bib);

    /** 查询赛事团队得分（OPEN 为重算结果，SEALED 为封榜固化快照）；只读。 */
    TeamStandingsResponse getTeamStandings(String raceId);
}
