package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoriesResponse;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.ConfigureCategoriesRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ReclassApplicationsResponse;
import com.example.starter.race.api.ReclassRecordsResponse;
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

    /** 一次性配置赛事组别（1~10个、代码赛事内唯一、容量>=1），配置后不可修改。 */
    ServiceResult configureCategories(String raceId, ConfigureCategoriesRequest request);

    /** 选手退赛；退赛者不参与排名、不占组别容量，成绩与分段数据保留。 */
    ServiceResult withdrawRunner(String raceId, WithdrawRunnerRequest request);

    /** 提交组别重分申请；仅 OPEN 赛事，目标组别必须存在且未封榜。 */
    ServiceResult submitReclassApplication(String raceId, SubmitReclassApplicationRequest request);

    /** 拒绝重分申请；只改变申请状态，不改变任何成绩。 */
    ServiceResult rejectReclassApplication(String raceId, String applicationId, RejectReclassRequest request);

    /**
     * 批量批准重分申请：先校验每个参赛者仅一次、目标组别容量未超、无申请涉及已封榜组别，
     * 任一不合法整批422；成功后在同一事务迁移全部选手组别、重算来源与目标组别排名
     * 并写入不可变重分记录（保留迁移前名次与计时快照）。
     */
    ServiceResult approveReclassBatch(String raceId, ApproveReclassRequest request);

    /** 组别封榜：固化该组别当前在赛选手的只读成绩快照；已封榜组别不再参与重分。 */
    ServiceResult sealCategory(String raceId, String categoryCode, SealCategoryRequest request);

    /** 查询赛事全部组别；只读。 */
    CategoriesResponse listCategories(String raceId);

    /** 查询单组别成绩榜（未封榜实时计算；已封榜返回组别快照）；只读。 */
    CategoryStandingResponse getCategoryResults(String raceId, String categoryCode);

    /** 查询组别封榜只读快照；未封榜为404。 */
    CategoryStandingResponse getCategorySnapshot(String raceId, String categoryCode);

    /** 查询赛事全部重分申请；只读。 */
    ReclassApplicationsResponse getReclassApplications(String raceId);

    /** 查询赛事全部不可变重分记录；只读。 */
    ReclassRecordsResponse getReclassRecords(String raceId);
}