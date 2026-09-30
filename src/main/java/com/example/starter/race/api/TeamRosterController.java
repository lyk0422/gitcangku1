package com.example.starter.race.api;

import com.example.starter.race.service.ServiceResult;
import com.example.starter.race.service.TeamRosterService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队名单锁定与团队成绩 HTTP 接口。
 */
@RestController
public class TeamRosterController {

    private final TeamRosterService teamRosterService;

    public TeamRosterController(TeamRosterService teamRosterService) {
        this.teamRosterService = teamRosterService;
    }

    /** 封榜前为团队锁定一个完整名单版本。 */
    @PostMapping("/api/races/{raceId}/teams/{teamId}/roster-locks")
    public ResponseEntity<Object> lockRoster(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @Valid @RequestBody LockRosterRequest request) {
        return toResponse(teamRosterService.lockRoster(raceId, teamId, request));
    }

    /** 登记成员退赛（生效场次与原因），不从锁定名单删人。 */
    @PostMapping("/api/races/{raceId}/teams/{teamId}/members/{bib}/withdrawal")
    public ResponseEntity<Object> withdrawMember(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @PathVariable String bib,
            @Valid @RequestBody WithdrawMemberRequest request) {
        return toResponse(teamRosterService.withdrawMember(raceId, teamId, bib, request));
    }

    /** 查询团队当前名单（最新锁定版本合并退赛状态）。 */
    @GetMapping("/api/races/{raceId}/teams/{teamId}/roster")
    public RosterResponse getCurrentRoster(
            @PathVariable String raceId,
            @PathVariable String teamId) {
        return teamRosterService.getCurrentRoster(raceId, teamId);
    }

    /** 查询团队指定历史锁定版本（只读原样）。 */
    @GetMapping("/api/races/{raceId}/teams/{teamId}/roster/versions/{version}")
    public RosterResponse getRosterVersion(
            @PathVariable String raceId,
            @PathVariable String teamId,
            @PathVariable int version) {
        return teamRosterService.getRosterVersion(raceId, teamId, version);
    }

    /** 查询团队成绩榜（OPEN 实时汇总；SEALED 返回封榜只读快照）。 */
    @GetMapping("/api/races/{raceId}/team-results")
    public TeamStandingResponse getTeamResults(
            @PathVariable String raceId,
            @RequestParam(name = "session", required = false) Integer session) {
        return teamRosterService.getTeamResults(raceId, session);
    }

    /** 查询封榜团队只读快照。 */
    @GetMapping("/api/races/{raceId}/team-snapshot")
    public TeamStandingResponse getTeamSnapshot(@PathVariable String raceId) {
        return teamRosterService.getTeamSnapshot(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
