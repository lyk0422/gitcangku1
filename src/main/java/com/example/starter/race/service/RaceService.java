package com.example.starter.race.service;

import com.example.starter.race.api.AccountResponse;
import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicateProtestRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureDepositRequest;
import com.example.starter.race.api.CreateAccountRequest;
import com.example.starter.race.api.CreateProtestRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LedgerResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RechargeRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.ProtestResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;

import java.util.List;

/**
 * 赛事成绩封榜应用服务；每个写方法在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交。
 */
public interface RaceService {

    /** 新建赛事（初始版本1、OPEN，保证金未配置）。 */
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

    /**
     * 封榜：校验版本并原子保存只读成绩快照（含每名选手分段明细与缺失检查点）。
     * 存在未裁决（PENDING）申诉时拒绝封榜（409），成绩暂缓封榜；
     * 封榜成功时把当时全部申诉与裁决依据固化进快照。
     */
    ServiceResult sealRace(String raceId, SealRaceRequest request);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照，含申诉裁决依据）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);

    /** 开立选手保证金账户（初始余额0）；账户ID重复返回409。 */
    ServiceResult createAccount(CreateAccountRequest request);

    /** 账户充值（金额单位：分，必须为正），写一笔 RECHARGE 流水。 */
    ServiceResult recharge(String accountId, RechargeRequest request);

    /** 查询账户信息（可用余额，不含冻结）；不存在为404。 */
    AccountResponse getAccount(String accountId);

    /**
     * 配置赛事申诉保证金（单位：分）。仅 OPEN 可配置，推进赛事版本；
     * 配置只对之后创建的申诉生效，不改变已有申诉的快照金额。
     */
    ServiceResult configureDeposit(String raceId, ConfigureDepositRequest request);

    /**
     * 创建申诉：按赛事当前保证金配置冻结该金额作为本申诉快照。
     * 余额不足（或保证金未配置）时返回422，不创建申诉、不冻结、不锁成绩；
     * 创建成功后该赛事在全部申诉终态前暂缓封榜。
     */
    ServiceResult createProtest(String raceId, CreateProtestRequest request);

    /**
     * 裁决申诉：ACCEPT 全额退回保证金（余额加回+REFUND流水），
     * REJECT 全部没收（余额不加回，仅 FORFEIT 流水）；两者均为终态。
     * 对终态申诉重复裁决或改判返回409，不会再次退款或没收；账务与申诉状态同事务提交。
     */
    ServiceResult adjudicateProtest(String protestId, AdjudicateProtestRequest request);

    /** 查询单个申诉（含固定快照金额与裁决状态）；不存在为404。 */
    ProtestResponse getProtest(String protestId);

    /** 查询赛事下全部申诉，按创建时间与申诉ID稳定返回；只读。 */
    List<ProtestResponse> listProtests(String raceId);

    /** 查询账户全部保证金流水（冻结/退回/没收/充值），按入账先后返回；只读。 */
    List<LedgerResponse> getLedgers(String accountId);
}
