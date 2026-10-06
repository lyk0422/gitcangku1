package com.example.starter.calibration.api;

import java.util.Map;
import java.util.TreeMap;

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
import com.example.starter.calibration.api.dto.SessionReleaseRequest;
import com.example.starter.calibration.api.dto.SessionResponse;
import com.example.starter.calibration.service.IdempotentExecutor;
import com.example.starter.calibration.service.SessionReleaseService;
import com.example.starter.calibration.service.SessionService;

/**
 * 迟滞校准会话接口：创建、顺序追加、关闭、会话批量放行、明细查询。
 * 全部写操作以 requestId 幂等：同键同参重放首次完整响应，异参 409，失败不占键。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final SessionReleaseService sessionReleases;
    private final IdempotentExecutor idempotent;

    public SessionController(SessionService sessions, SessionReleaseService sessionReleases,
                             IdempotentExecutor idempotent) {
        this.sessions = sessions;
        this.sessionReleases = sessionReleases;
        this.idempotent = idempotent;
    }

    /**
     * 创建会话：201；参数非法 400；证书不存在 404；证书非迟滞类型或已撤销 422；会话键重复 409。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestBody CreateSessionRequest request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("sessionKey", request.sessionKey());
        params.put("instrumentId", request.instrumentId());
        params.put("certificateId", request.certificateId());
        params.put("lowerLimit", request.lowerLimit());
        params.put("upperLimit", request.upperLimit());
        return idempotent.run(request.requestId(), "SESSION_CREATE", request.createdBy(),
                String.valueOf(request.sessionKey()), params, HttpStatus.CREATED,
                () -> sessions.create(request));
    }

    /**
     * 顺序追加一条原始值：201；超精度等参数非法 400；会话不存在 404；
     * 版本冲突或会话已关闭 409；超过 20 条或证书撤销 422。
     */
    @PostMapping("/{key}/entries")
    public ResponseEntity<String> append(@PathVariable String key,
                                         @RequestBody AppendEntryRequest request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("expectedSessionVersion", request.expectedSessionVersion());
        params.put("rawValue", request.rawValue());
        params.put("direction", request.direction());
        return idempotent.run(request.requestId(), "SESSION_APPEND", request.submittedBy(),
                key, params, HttpStatus.CREATED,
                () -> sessions.append(key, request));
    }

    /**
     * 关闭会话：200；会话不存在 404；版本冲突或重复关闭 409；空会话 422。
     */
    @PostMapping("/{key}/close")
    public ResponseEntity<String> close(@PathVariable String key,
                                        @RequestBody CloseSessionRequest request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("expectedSessionVersion", request.expectedSessionVersion());
        return idempotent.run(request.requestId(), "SESSION_CLOSE", request.closedBy(),
                key, params, HttpStatus.OK,
                () -> sessions.close(key, request));
    }

    /**
     * 会话批量放行（1～50 个会话，原子）：200；批次非法 400；
     * 任一会话不满足条件（未关闭、成员不合格、证书撤销、放行人撞提交人）整批 422。
     * 放行人通过 X-Actor-Id 请求头提供。
     */
    @PostMapping("/release")
    public ResponseEntity<String> release(@RequestBody SessionReleaseRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        Map<String, Object> params = new TreeMap<>();
        params.put("keys", request.keys() == null ? null : request.keys().stream().sorted().toList());
        return idempotent.run(request.requestId(), "SESSION_RELEASE", actor,
                "sessions", params, HttpStatus.OK,
                () -> sessionReleases.release(request.keys(), actor));
    }

    /**
     * 会话明细：会话与全部条目（方向、未舍入校正值、显示值）；不存在 404。
     */
    @GetMapping("/{key}")
    public SessionResponse detail(@PathVariable String key) {
        return sessions.detail(key);
    }
}
