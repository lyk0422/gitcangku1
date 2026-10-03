package com.example.starter.cue.web;

import com.example.starter.cue.domain.CueExecution;
import com.example.starter.cue.domain.RehearsalSession;
import com.example.starter.cue.service.RehearsalService;
import com.example.starter.cue.web.dto.CreateSessionRequest;
import com.example.starter.cue.web.dto.ReportRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 排练场次接口：建场、重绑版本、开始冻结、按序回报、结束与结果查询。
 */
@RestController
@RequestMapping("/api/sessions")
public class RehearsalController {

    private final RehearsalService service;

    public RehearsalController(RehearsalService service) {
        this.service = service;
    }

    /** 新建未开始场次，返回场次 ID。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody CreateSessionRequest request) {
        long sessionId = service.createSession(request.production(), request.versionId());
        return Map.of("sessionId", sessionId);
    }

    /** 未开始场次重新绑定剧目最新冻结版本。 */
    @PostMapping("/{sessionId}/rebind")
    public RehearsalSession rebind(@PathVariable long sessionId) {
        return service.rebind(sessionId);
    }

    /** 开始排练并冻结提示点快照。 */
    @PostMapping("/{sessionId}/start")
    public RehearsalSession start(@PathVariable long sessionId) {
        return service.start(sessionId);
    }

    /** 按序回报提示点执行结果。 */
    @PostMapping("/{sessionId}/reports")
    public CueExecution report(@PathVariable long sessionId,
                               @Valid @RequestBody ReportRequest request) {
        return service.report(sessionId, request.requestId(), request.seq(),
                request.operator(), request.result(), request.failReason());
    }

    /** 结束排练，按已提交序号和完整性给出结论。 */
    @PostMapping("/{sessionId}/end")
    public RehearsalSession end(@PathVariable long sessionId) {
        return service.end(sessionId);
    }

    /** 查询场次冻结序列及每个提示点的执行或失败结果。 */
    @GetMapping("/{sessionId}")
    public Map<String, Object> describe(@PathVariable long sessionId) {
        return service.describeSession(sessionId);
    }
}