package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CategoryResponse;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplicationResponse;
import com.example.starter.race.api.ReclassRecordResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;

import java.util.List;

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

    /** 新建组别（初始 OPEN）；组别代码重复返回409，赛事已封榜或版本冲突返回409。 */
    ServiceResult createCategory(String raceId, CreateCategoryRequest request);

    /** 选手退赛；退赛后不排名、不占容量、不可再重分；已退赛或已封榜返回409。 */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /**
     * 提交组别重分申请；目标组别不存在或已封榜返回422，
     * 选手已退赛或已有待审批申请返回409。
     */
    ServiceResult submitReclassApplication(String raceId, SubmitReclassApplicationRequest request);

    /** 拒绝重分申请；仅状态迁移为 REJECTED，不改变任何成绩；非待审批返回409。 */
    ServiceResult rejectReclassApplication(
            String raceId, String applicationId, RejectReclassRequest request);

    /**
     * 批量批准重分申请：同一事务内校验（批内每选手仅一次、目标组别容量未超、
     * 无申请涉及已封榜目标组别，违反整批422；来源组别已封榜或选手已退赛整批409）、
     * 迁移全部选手组别、重算来源与目标组别排名并写入不可变重分记录。
     * reclassKey 同键成功重放完整排名结果，失败不占键。
     */
    ServiceResult approveReclassApplications(String raceId, ApproveReclassRequest request);

    /** 组别封榜：仅固化封榜时刻归属该组别选手的成绩快照；组别已封榜返回409。 */
    ServiceResult sealCategory(String raceId, String categoryCode, SealCategoryRequest request);

    /** 查询赛事全部组别（含容量与当前在赛人数）；只读。 */
    List<CategoryResponse> listCategories(String raceId);

    /** 查询赛事全部重分申请；只读。 */
    List<ReclassApplicationResponse> listReclassApplications(String raceId);

    /** 查询赛事全部不可变重分记录；只读。 */
    List<ReclassRecordResponse> listReclassRecords(String raceId);

    /** 查询组别排名（OPEN 实时计算；SEALED 返回封榜快照）；只读。 */
    CategoryStandingResponse getCategoryStandings(String raceId, String categoryCode);

    /** 查询赛事全部组别封榜历史快照；只读。 */
    List<CategoryStandingResponse> listCategorySnapshots(String raceId);
}
