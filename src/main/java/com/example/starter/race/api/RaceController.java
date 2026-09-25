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

    /** 创建队伍（队长自动成为首位成员）。 */
    @PostMapping("/{raceId}/teams")
    public ResponseEntity<Object> createTeam(
            @PathVariable String raceId,
            @Valid @RequestBody CreateTeamRequest request) {
        return toResponse(raceService.createTeam(raceId, request));
    }

    /** 新增队伍成员（仅未锁定时）。 */
    @PostMapping("/{raceId}/teams/{teamId}/members")
    public ResponseEntity<Object> addTeamMember(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @Valid @RequestBody AddTeamMemberRequest request) {
        return toResponse(raceService.addTeamMember(raceId, teamId, request));
    }

    /** 移除队伍成员（仅未锁定时，队长不可移除）。 */
    @PostMapping("/{raceId}/teams/{teamId}/members/{bib}/removal")
    public ResponseEntity<Object> removeTeamMember(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @PathVariable String bib,
            @Valid @RequestBody RemoveTeamMemberRequest request) {
        return toResponse(raceService.removeTeamMember(raceId, teamId, bib, request));
    }

    /** 批量锁定队伍名单（任一校验失败整批422）。 */
    @PostMapping("/{raceId}/roster-locks")
    public ResponseEntity<Object> lockRosters(
            @PathVariable String raceId,
            @Valid @RequestBody LockRostersRequest request) {
        return toResponse(raceService.lockRosters(raceId, request));
    }

    /** 裁判解锁队伍名单（须说明原因，仅封榜前）。 */
    @PostMapping("/{raceId}/teams/{teamId}/roster-unlock")
    public ResponseEntity<Object> unlockRoster(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @Valid @RequestBody UnlockRosterRequest request) {
        return toResponse(raceService.unlockRoster(raceId, teamId, request));
    }

    /** 查询队伍名单版本与当前名单。 */
    @GetMapping("/{raceId}/teams/{teamId}/roster")
    public RosterResponse getRoster(
            @PathVariable String raceId,
            @PathVariable String teamId) {
        return raceService.getRoster(raceId, teamId);
    }

    /** 查询参赛者个人归属。 */
    @GetMapping("/{raceId}/runners/{bib}/team")
    public RunnerTeamResponse getRunnerTeam(
            @PathVariable String raceId,
            @PathVariable String bib) {
        return raceService.getRunnerTeam(raceId, bib);
    }

    /** 查询团队得分（OPEN 实时重算；SEALED 返回固化内容）。 */
    @GetMapping("/{raceId}/team-standings")
    public TeamStandingsResponse getTeamStandings(@PathVariable String raceId) {
        return raceService.getTeamStandings(raceId);
    }

    /** 查询封榜队伍成绩快照。 */
    @GetMapping("/{raceId}/team-snapshot")
    public TeamSnapshotResponse getTeamSnapshot(@PathVariable String raceId) {
        return raceService.getTeamSnapshot(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
