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

    /** 封榜前锁定团队的一个名单版本。 */
    @PostMapping("/{raceId}/teams/{teamId}/rosters")
    public ResponseEntity<Object> lockRoster(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @Valid @RequestBody LockRosterRequest request) {
        return toResponse(raceService.lockRoster(raceId, teamId, request));
    }

    /** 登记团队成员退赛（生效场次与原因，不删名单成员）。 */
    @PostMapping("/{raceId}/teams/{teamId}/withdrawals")
    public ResponseEntity<Object> withdrawRosterMember(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @Valid @RequestBody WithdrawRosterMemberRequest request) {
        return toResponse(raceService.withdrawRosterMember(raceId, teamId, request));
    }

    /** 查询团队当前名单（最新锁定版本叠加退赛状态）。 */
    @GetMapping("/{raceId}/teams/{teamId}/roster")
    public CurrentRosterResponse getCurrentRoster(
            @PathVariable String raceId,
            @PathVariable String teamId) {
        return raceService.getCurrentRoster(raceId, teamId);
    }

    /** 查询团队指定的已锁定名单版本（只读固化快照）。 */
    @GetMapping("/{raceId}/teams/{teamId}/rosters/{versionNo}")
    public TeamRosterResponse getRosterVersion(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @PathVariable int versionNo) {
        return raceService.getRosterVersion(raceId, teamId, versionNo);
    }

    /** 查询团队全部已锁定名单版本头（版本号升序）。 */
    @GetMapping("/{raceId}/teams/{teamId}/rosters")
    public java.util.List<TeamRosterResponse> getRosterVersions(
            @PathVariable String raceId,
            @PathVariable String teamId) {
        return raceService.getRosterVersions(raceId, teamId);
    }

    /** 查询团队成绩榜（OPEN 实时；SEALED 返回封榜只读团队榜）。 */
    @GetMapping("/{raceId}/team-results")
    public TeamStandingsResponse getTeamResults(@PathVariable String raceId) {
        return raceService.getTeamResults(raceId);
    }

    /** 查询封榜团队榜只读快照。 */
    @GetMapping("/{raceId}/team-snapshot")
    public TeamStandingsResponse getTeamSnapshot(@PathVariable String raceId) {
        return raceService.getTeamSnapshot(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
