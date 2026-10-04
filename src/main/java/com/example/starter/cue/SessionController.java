package com.example.starter.cue;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 排练场次接口。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 创建未开始场次并绑定版本，返回场次ID。 */
    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody CueDtos.CreateSessionRequest request) {
        long sessionId = sessionService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("sessionId", sessionId));
    }

    /** 未开始场次重新绑定最新版本并重新校验全部提示点。 */
    @PostMapping("/{id}/rebind")
    public RehearsalSession rebind(@PathVariable long id) {
        return sessionService.rebindToLatest(id);
    }

    /** 开始排练，冻结提示点版本及顺序。 */
    @PostMapping("/{id}/start")
    public RehearsalSession start(@PathVariable long id) {
        return sessionService.start(id);
    }

    /** 按序回报提示点执行结果。 */
    @PostMapping("/{id}/reports")
    public CueDtos.ReportResponse report(@PathVariable long id,
                                         @RequestBody CueDtos.ReportRequest request) {
        return sessionService.report(id, request);
    }

    /** 结束场次，仅当全部提示点已执行完成。 */
    @PostMapping("/{id}/finish")
    public RehearsalSession finish(@PathVariable long id) {
        return sessionService.finish(id);
    }

    /** 查询场次冻结序列与每个提示点的执行或失败结果。 */
    @GetMapping("/{id}")
    public CueDtos.SessionDetail detail(@PathVariable long id) {
        return sessionService.detail(id);
    }
}