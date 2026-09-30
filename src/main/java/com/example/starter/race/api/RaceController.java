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
 * 赛事计时处罚与成绩封榜 HTTP 接口。
 */
@RestController
@RequestMapping("/api/races")
public class RaceController {

    private final RaceService raceService;

    public RaceController(RaceService raceService) {
        this.raceService = raceService;
    }

    /** 新建赛事。 */
    @PostMapping
    public ResponseEntity<Object> createRace(@Valid @RequestBody CreateRaceRequest request) {
        return toResponse(raceService.createRace(request));
    }

    /** 登记选手。 */
    @PostMapping("/{raceId}/runners")
    public ResponseEntity<Object> registerRunner(
            @PathVariable String raceId,
            @Valid @RequestBody RegisterRunnerRequest request) {
        return toResponse(raceService.registerRunner(raceId, request));
    }

    /** 计时修订。 */
    @PostMapping("/{raceId}/timing-revisions")
    public ResponseEntity<Object> reviseTime(
            @PathVariable String raceId,
            @Valid @RequestBody ReviseTimeRequest request) {
        return toResponse(raceService.reviseTime(raceId, request));
    }

    /** 新增处罚。 */
    @PostMapping("/{raceId}/penalties")
    public ResponseEntity<Object> addPenalty(
            @PathVariable String raceId,
            @Valid @RequestBody AddPenaltyRequest request) {
        return toResponse(raceService.addPenalty(raceId, request));
    }

    /** 撤销处罚。 */
    @PostMapping("/{raceId}/penalties/{penaltyId}/revocation")
    public ResponseEntity<Object> revokePenalty(
            @PathVariable String raceId,
            @PathVariable String penaltyId,
            @Valid @RequestBody RevokePenaltyRequest request) {
        return toResponse(raceService.revokePenalty(raceId, penaltyId, request));
    }

    /** 一次性配置赛事检查点。 */
    @PostMapping("/{raceId}/checkpoints")
    public ResponseEntity<Object> configureCheckpoints(
            @PathVariable String raceId,
            @Valid @RequestBody ConfigureCheckpointsRequest request) {
        return toResponse(raceService.configureCheckpoints(raceId, request));
    }

    /** 为选手提交检查点通过记录。 */
    @PostMapping("/{raceId}/runners/{bib}/timings")
    public ResponseEntity<Object> submitTiming(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody SubmitTimingRequest request) {
        return toResponse(raceService.submitTiming(raceId, bib, request));
    }

    /** 封榜。 */
    @PostMapping("/{raceId}/seal")
    public ResponseEntity<Object> sealRace(
            @PathVariable String raceId,
            @Valid @RequestBody SealRaceRequest request) {
        return toResponse(raceService.sealRace(raceId, request));
    }

    /** 查询即时成绩（封榜后返回只读快照内容）。 */
    @GetMapping("/{raceId}/results")
    public StandingResponse getResults(@PathVariable String raceId) {
        return raceService.getResults(raceId);
    }

    /** 查询封榜快照。 */
    @GetMapping("/{raceId}/snapshot")
    public StandingResponse getSnapshot(@PathVariable String raceId) {
        return raceService.getSnapshot(raceId);
    }

    /** 查询单个选手的分段明细（按检查点顺序）。 */
    @GetMapping("/{raceId}/runners/{bib}/timings")
    public RunnerTimingResponse getRunnerTimings(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getRunnerTimings(raceId, bib);
    }

    /** 查询赛事缺失检查点汇总。 */
    @GetMapping("/{raceId}/missing-checkpoints")
    public MissingCheckpointsResponse getMissingCheckpoints(@PathVariable String raceId) {
        return raceService.getMissingCheckpoints(raceId);
    }

    /** 配置申诉保证金（只影响之后新建申诉的冻结金额）。 */
    @PostMapping("/{raceId}/deposit-config")
    public ResponseEntity<Object> configureDeposit(
            @PathVariable String raceId,
            @Valid @RequestBody ConfigureDepositRequest request) {
        return toResponse(raceService.configureDeposit(raceId, request));
    }

    /** 选手余额充值（合成数据入口）。 */
    @PostMapping("/{raceId}/runners/{bib}/wallet-topups")
    public ResponseEntity<Object> topUpWallet(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody TopUpWalletRequest request) {
        return toResponse(raceService.topUpWallet(raceId, bib, request));
    }

    /** 查询选手钱包余额。 */
    @GetMapping("/{raceId}/runners/{bib}/wallet")
    public WalletResponse getWallet(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getWallet(raceId, bib);
    }

    /** 查询选手冻结/退回/没收及充值流水。 */
    @GetMapping("/{raceId}/runners/{bib}/wallet-transactions")
    public List<WalletTransactionResponse> getWalletTransactions(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getWalletTransactions(raceId, bib);
    }

    /** 创建成绩申诉并按赛事当前配置冻结保证金。 */
    @PostMapping("/{raceId}/runners/{bib}/protests")
    public ResponseEntity<Object> createProtest(
            @PathVariable String raceId,
            @PathVariable String bib,
            @Valid @RequestBody CreateProtestRequest request) {
        return toResponse(raceService.createProtest(raceId, bib, request));
    }

    /** 终态裁决申诉：ACCEPT 全额退回，REJECT 全部没收。 */
    @PostMapping("/{raceId}/protests/{protestId}/decision")
    public ResponseEntity<Object> decideProtest(
            @PathVariable String raceId,
            @PathVariable String protestId,
            @Valid @RequestBody DecideProtestRequest request) {
        return toResponse(raceService.decideProtest(raceId, protestId, request));
    }

    /** 查询单个申诉。 */
    @GetMapping("/{raceId}/protests/{protestId}")
    public ProtestResponse getProtest(
            @PathVariable String raceId,
            @PathVariable String protestId) {
        return raceService.getProtest(raceId, protestId);
    }

    /** 查询赛事全部申诉（含当时的状态与保证金快照）。 */
    @GetMapping("/{raceId}/protests")
    public ProtestListResponse getProtests(@PathVariable String raceId) {
        return raceService.getProtests(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
