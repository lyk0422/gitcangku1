package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdvancementResponse;
import com.example.starter.race.api.AssignGroupsRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.GenerateAdvancementRequest;
import com.example.starter.race.api.GroupsResponse;
import com.example.starter.race.api.NonAdvancedResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokeAdvancementRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;

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

    /**
     * 一次性划分分组（2~8 组、每组 2~16 人、同一选手只属一组）；
     * 仅 OPEN 且尚未划分时可执行，成功后版本加一且不可改写。
     */
    ServiceResult assignGroups(String raceId, AssignGroupsRequest request);

    /**
     * 原子生成晋级名单：组内前 Q 名直接晋级，剩余有效选手全局前 W 名补位，
     * 并列跨过边界全部纳入；任一分组有效选手不足 Q 名返回422且不生成。
     * 成功后写入不可变快照并推进版本；同一赛事最多一份生效名单。
     */
    ServiceResult generateAdvancement(String raceId, GenerateAdvancementRequest request);

    /** 整份撤销当前生效名单并推进版本；原快照保留，撤销后可重新生成。 */
    ServiceResult revokeAdvancement(String raceId, RevokeAdvancementRequest request);

    /** 查询赛事分组划分；未划分为空列表。 */
    GroupsResponse getGroups(String raceId);

    /** 查询当前生效的晋级名单；无生效名单为404。 */
    AdvancementResponse getActiveAdvancement(String raceId);

    /** 按键查询晋级名单快照（含已撤销）；不存在或不属于该赛事为404。 */
    AdvancementResponse getAdvancement(String raceId, String advancementKey);

    /** 查询未晋级清单：已划入分组但不在当前生效名单中的选手；无生效名单为404。 */
    NonAdvancedResponse getNonAdvanced(String raceId);
}
