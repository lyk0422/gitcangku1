package com.example.starter.race.api;

import com.example.starter.race.service.RaceService;
import com.example.starter.race.service.ServiceResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
/**
 * 赛事计时处罚、成绩封榜与申诉保证金 HTTP 接口。
 */
@RestController
@RequestMapping("/api")
public class RaceController {

    private final RaceService raceService;

    public RaceController(RaceService raceService) {
        this.raceService = raceService;
    }

    /** 新建赛事。 */
    @PostMapping("/races")
    public ResponseEntity<Object> createRace(@Valid @RequestBody CreateRaceRequest request) {
        return toResponse(raceService.createRace(request));
    }

    /** 登记选手。 */
    @PostMapping("/races/{raceId}/runners")
    public ResponseEntity<Object> registerRunner(
            @PathVariable String raceId,
            @Valid @RequestBody RegisterRunnerRequest request) {
        return toResponse(raceService.registerRunner(raceId, request));
    }

    /** 计时修订。 */
    @PostMapping("/races/{raceId}/timing-revisions")
    public ResponseEntity<Object> reviseTime(
            @PathVariable String raceId,
            @Valid @RequestBody ReviseTimeRequest request) {
        return toResponse(raceService.reviseTime(raceId, request));
    }

    /** 新增处罚。 */
    @PostMapping("/races/{raceId}/penalties")
    public ResponseEntity<Object> addPenalty(
            @PathVariable String raceId,
            @Valid @RequestBody AddPenaltyRequest request) {
        return toResponse(raceService.addPenalty(raceId, request));
    }

    /** 撤销处罚。 */
    @PostMapping("/races/{raceId}/penalties/{penaltyId}/revocation")
    public ResponseEntity<Object> revokePenalty(
            @PathVariable String raceId,
            @PathVariable String penaltyId,
            @Valid @RequestBody RevokePenaltyRequest request) {
        return toResponse(raceService.revokePenalty(raceId, penaltyId, request));
    }

    /** 一次性配置赛事检查点。 */
    @PostMapping("/races/{raceId}/checkpoints")
    public ResponseEntity<Object> configureCheckpoints(
            @PathVariable String raceId,
            @Valid @RequestBody ConfigureCheckpointsRequest request) {
        return toResponse(raceService.configureCheckpoints(raceId, request));
    }

    /** 为选手提交检查点通过记录。 */
    @PostMapping("/races/{raceId}/runners/{bib}/timings")
    public ResponseEntity<Object> submitTiming(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody SubmitTimingRequest request) {
        return toResponse(raceService.submitTiming(raceId, bib, request));
    }

    /** 配置赛事申诉保证金（金额单位：分，仅对之后创建的申诉生效）。 */
    @PostMapping("/races/{raceId}/deposit-config")
    public ResponseEntity<Object> configureDeposit(
            @PathVariable String raceId,
            @Valid @RequestBody ConfigureDepositRequest request) {
        return toResponse(raceService.configureDeposit(raceId, request));
    }

    /** 创建申诉：按赛事当前配置冻结保证金快照；余额不足返回422且不创建。 */
    @PostMapping("/races/{raceId}/protests")
    public ResponseEntity<Object> createProtest(
            @PathVariable String raceId,
            @Valid @RequestBody CreateProtestRequest request) {
        return toResponse(raceService.createProtest(raceId, request));
    }

    /** 查询赛事下全部申诉（含固定快照金额与裁决状态）。 */
    @GetMapping("/races/{raceId}/protests")
    public List<ProtestResponse> listProtests(@PathVariable String raceId) {
        return raceService.listProtests(raceId);
    }

    /** 封榜。 */
    @PostMapping("/races/{raceId}/seal")
    public ResponseEntity<Object> sealRace(
            @PathVariable String raceId,
            @Valid @RequestBody SealRaceRequest request) {
        return toResponse(raceService.sealRace(raceId, request));
    }

    /** 查询即时成绩（封榜后返回只读快照内容，含申诉裁决依据）。 */
    @GetMapping("/races/{raceId}/results")
    public StandingResponse getResults(@PathVariable String raceId) {
        return raceService.getResults(raceId);
    }

    /** 查询封榜快照。 */
    @GetMapping("/races/{raceId}/snapshot")
    public StandingResponse getSnapshot(@PathVariable String raceId) {
        return raceService.getSnapshot(raceId);
    }

    /** 查询单个选手的分段明细（按检查点顺序）。 */
    @GetMapping("/races/{raceId}/runners/{bib}/timings")
    public RunnerTimingResponse getRunnerTimings(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getRunnerTimings(raceId, bib);
    }

    /** 查询赛事缺失检查点汇总。 */
    @GetMapping("/races/{raceId}/missing-checkpoints")
    public MissingCheckpointsResponse getMissingCheckpoints(@PathVariable String raceId) {
        return raceService.getMissingCheckpoints(raceId);
    }

    /** 开立选手保证金账户。 */
    @PostMapping("/accounts")
    public ResponseEntity<Object> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return toResponse(raceService.createAccount(request));
    }

    /** 查询账户信息（可用余额，不含冻结）。 */
    @GetMapping("/accounts/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return raceService.getAccount(accountId);
    }

    /** 账户充值（金额单位：分）。 */
    @PostMapping("/accounts/{accountId}/recharges")
    public ResponseEntity<Object> recharge(
            @PathVariable String accountId,
            @Valid @RequestBody RechargeRequest request) {
        return toResponse(raceService.recharge(accountId, request));
    }

    /** 查询账户保证金流水（冻结/退回/没收/充值）。 */
    @GetMapping("/accounts/{accountId}/ledger")
    public List<LedgerResponse> getLedgers(@PathVariable String accountId) {
        return raceService.getLedgers(accountId);
    }

    /** 查询单个申诉。 */
    @GetMapping("/protests/{protestId}")
    public ProtestResponse getProtest(@PathVariable String protestId) {
        return raceService.getProtest(protestId);
    }

    /** 裁决申诉：ACCEPT 全额退回，REJECT 全部没收；终态重复裁决或改判返回409。 */
    @PostMapping("/protests/{protestId}/adjudication")
    public ResponseEntity<Object> adjudicateProtest(
            @PathVariable String protestId,
            @Valid @RequestBody AdjudicateProtestRequest request) {
        return toResponse(raceService.adjudicateProtest(protestId, request));
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
