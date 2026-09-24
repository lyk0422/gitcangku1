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

    /**
     * 一次性分组划分（2~8 组、每组 2~16 人、选手跨组唯一）；
     * 仅 OPEN 且未划分过的赛事可提交，成功后版本加一且不可改写。
     */
    ServiceResult assignGroups(String raceId, AssignGroupsRequest request);

    /**
     * 事务内生成不可变晋级名单：组内前 Q 名 DIRECT，未直接晋级者中全局前 W 名 WILDCARD；
     * 任一分组有效选手不足 Q 名返回422并指明该分组；重复生成409；advancementKey 全局唯一。
     */
    ServiceResult generateAdvancement(String raceId, GenerateAdvancementRequest request);

    /** 整份撤销当前生效名单（原快照保留为 REVOKED），之后可重新生成；封榜后409。 */
    ServiceResult revokeAdvancement(String raceId, RevokeAdvancementRequest request);

    /** 查询分组划分结果；赛事或分组不存在为404。 */
    GroupsResponse getGroups(String raceId);

    /** 查询当前生效晋级名单（不可变快照内容）；无生效名单为404。 */
    AdvancementResponse getActiveAdvancement(String raceId);

    /** 查询随生效名单固化的未晋级清单；无生效名单为404。 */
    NonAdvancedResponse getNonAdvanced(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);
}
