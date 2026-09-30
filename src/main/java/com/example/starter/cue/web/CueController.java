package com.example.starter.cue.web;

import com.example.starter.cue.dto.BindVersionRequest;
import com.example.starter.cue.dto.CreateSessionRequest;
import com.example.starter.cue.dto.CreateVersionRequest;
import com.example.starter.cue.dto.ReportCueRequest;
import com.example.starter.cue.dto.ReportResponse;
import com.example.starter.cue.dto.SessionDetailResponse;
import com.example.starter.cue.dto.SessionResponse;
import com.example.starter.cue.dto.VersionDiffResponse;
import com.example.starter.cue.dto.VersionResponse;
import com.example.starter.cue.service.CueService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 剧目提示点编排、排练冻结与执行回报的 HTTP 接口。
 */
@RestController
public class CueController {

    private final CueService cueService;

    public CueController(CueService cueService) {
        this.cueService = cueService;
    }

    /**
     * 创建编排版本（编排修改即新建不可变版本）。
     */
    @PostMapping("/api/cue-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse createVersion(@Valid @RequestBody CreateVersionRequest request) {
        return cueService.createVersion(request);
    }

    /**
     * 查询指定版本及其提示点顺序。
     */
    @GetMapping("/api/plays/{playCode}/cue-versions/{versionNo}")
    public VersionResponse getVersion(@PathVariable String playCode,
                                      @PathVariable int versionNo) {
        return cueService.getVersion(playCode, versionNo);
    }

    /**
     * 展示两个版本的差异（新增/删除/变化的提示点）。
     */
    @GetMapping("/api/plays/{playCode}/cue-versions/diff")
    public VersionDiffResponse diff(@PathVariable String playCode,
                                    @RequestParam int from,
                                    @RequestParam int to) {
        return cueService.diffVersions(playCode, from, to);
    }

    /**
     * 创建未开始排练场次，可选立即绑定版本。
     */
    @PostMapping("/api/rehearsal-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse createSession(@Valid @RequestBody CreateSessionRequest request) {
        return cueService.createSession(request);
    }

    /**
     * 查询场次冻结序列与每个提示点的执行/失败结果。
     */
    @GetMapping("/api/plays/{playCode}/rehearsal-sessions/{sessionCode}")
    public SessionDetailResponse getSession(@PathVariable String playCode,
                                            @PathVariable String sessionCode) {
        return cueService.getSessionDetail(playCode, sessionCode);
    }

    /**
     * 未开始场次重新绑定最新（或指定）版本，绑定前重新校验全部提示点。
     */
    @PostMapping("/api/plays/{playCode}/rehearsal-sessions/{sessionCode}/bind")
    public SessionResponse bindVersion(@PathVariable String playCode,
                                       @PathVariable String sessionCode,
                                       @RequestBody(required = false) BindVersionRequest request) {
        return cueService.bindVersion(playCode, sessionCode,
                request == null ? new BindVersionRequest(null) : request);
    }

    /**
     * 开始排练：冻结提示点版本与顺序。
     */
    @PostMapping("/api/plays/{playCode}/rehearsal-sessions/{sessionCode}/start")
    public SessionResponse start(@PathVariable String playCode,
                                 @PathVariable String sessionCode) {
        return cueService.startSession(playCode, sessionCode);
    }

    /**
     * 操作员回报提示点执行结果；跳号 409，失败记录原因不推进，requestId 幂等。
     */
    @PostMapping("/api/plays/{playCode}/rehearsal-sessions/{sessionCode}/reports")
    public ReportResponse report(@PathVariable String playCode,
                                 @PathVariable String sessionCode,
                                 @Valid @RequestBody ReportCueRequest request) {
        return cueService.report(playCode, sessionCode, request);
    }

    /**
     * 结束本场；仅冻结序列全部成功执行才可结束，与末条回报并发时按已提交状态判定。
     */
    @PostMapping("/api/plays/{playCode}/rehearsal-sessions/{sessionCode}/finish")
    public SessionResponse finish(@PathVariable String playCode,
                                  @PathVariable String sessionCode) {
        return cueService.finishSession(playCode, sessionCode);
    }
}
