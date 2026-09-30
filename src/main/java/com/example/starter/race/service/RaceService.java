package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureDepositRequest;
import com.example.starter.race.api.CreateProtestRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.DecideProtestRequest;
import com.example.starter.race.api.DepositConfigResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.ProtestListResponse;
import com.example.starter.race.api.ProtestResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TopUpWalletRequest;
import com.example.starter.race.api.WalletResponse;
import com.example.starter.race.api.WalletTransactionResponse;

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

    /**
     * 配置申诉保证金金额（人民币元正整数）；新版本生效，只影响之后新建的申诉，
     * 已有申诉按创建时快照金额冻结/退回/没收。版本不匹配或已封榜返回409。
     */
    ServiceResult configureDeposit(String raceId, ConfigureDepositRequest request);

    /**
     * 选手余额充值（合成数据入口）；同 transactionId 重放首次结果，异参409。
     * 版本不匹配或已封榜返回409。
     */
    ServiceResult topUpWallet(String raceId, String bib, TopUpWalletRequest request);

    /**
     * 创建申诉并冻结保证金：金额取赛事当前配置并固化为该申诉快照；
     * 同事务内条件扣减选手余额（FREEZE 流水 + balance_after）；
     * 余额不足返回422，不创建申诉、不扣余额、不锁成绩；同 protestId/同 transactionId 重放规则同其它写操作。
     * 版本不匹配或已封榜409；未配置保证金或选手不存在按约定处理。
     */
    ServiceResult createProtest(String raceId, String bib, CreateProtestRequest request);

    /**
     * 终态裁决：ACCEPT 条件退回保证金（条件 UPDATE protest PENDING→终态 + 条件恢复余额，
     * 保证重复裁决/并发裁决最多退款一次）并写 REFUND 流水；REJECT 条件转 REJECTED 并写 FORFEIT 流水。
     * 重复裁决或终态改判返回409，不会再次退款或没收。版本不匹配409。
     */
    ServiceResult decideProtest(
            String raceId, String protestId, DecideProtestRequest request);

    /** 查询单个申诉；不存在404。 */
    ProtestResponse getProtest(String raceId, String protestId);

    /** 查询赛事全部申诉，按创建时间、申诉ID稳定排列。 */
    ProtestListResponse getProtests(String raceId);

    /** 查询某选手全部资金流水（冻结/退回/没收及充值），按记账时间、流水ID稳定排列。 */
    List<WalletTransactionResponse> getWalletTransactions(String raceId, String bib);

    /** 查询某选手钱包余额。 */
    WalletResponse getWallet(String raceId, String bib);
}
