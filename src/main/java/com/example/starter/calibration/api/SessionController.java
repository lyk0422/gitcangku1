package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.calibration.api.dto.AppendEntryRequest;
import com.example.starter.calibration.api.dto.CloseSessionRequest;
import com.example.starter.calibration.api.dto.CreateSessionRequest;
import com.example.starter.calibration.api.dto.ReleaseSessionsRequest;
import com.example.starter.calibration.api.dto.SessionReleaseResponse;
import com.example.starter.calibration.api.dto.SessionResponse;
import com.example.starter.calibration.service.SessionReleaseService;
import com.example.starter.calibration.service.SessionService;

/**
 * 迟滞校准会话接口：创建、顺序追加、关闭、批量放行、明细查询。全部写操作按 requestId 幂等。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final SessionReleaseService sessionReleases;

    public SessionController(SessionService sessions, SessionReleaseService sessionReleases) {
        this.sessions = sessions;
        this.sessionReleases = sessionReleases;
    }

    /**
     * 创建会话：201；参数非法 400；证书不存在 404；线性证书 422；证书已撤销 409。
     */
    @PostMapping
    public ResponseEntity<SessionResponse> create(@RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sessions.create(request));
    }

    /**
     * 顺序追加条目：200；超精度等参数非法 400；版本冲突/已关闭/已满 409；会话不存在 404。
     */
    @PostMapping("/{id}/entries")
    public SessionResponse append(@PathVariable long id, @RequestBody AppendEntryRequest request) {
        return sessions.append(id, request);
    }

    /**
     * 关闭会话（冻结方向与精确结果）：200；版本冲突/已关闭 409；会话不存在 404。
     */
    @PostMapping("/{id}/close")
    public SessionResponse close(@PathVariable long id, @RequestBody CloseSessionRequest request) {
        return sessions.close(id, request);
    }

    /**
     * 会话批量放行（原子）：200；批次非法 400；任一会话不满足条件整批 422 并返回各项原因。
     * 放行人通过 X-Actor-Id 请求头提供，不得是任何成员提交人。
     */
    @PostMapping("/release")
    public SessionReleaseResponse release(@RequestBody ReleaseSessionsRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return sessionReleases.release(request, actor);
    }

    /**
     * 会话明细：200；不存在 404。
     */
    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable long id) {
        return sessions.get(id);
    }
}
