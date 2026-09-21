package com.example.starter.web;

import com.example.starter.consent.ConsentService;
import com.example.starter.consent.Purpose;
import com.example.starter.web.dto.GrantRequest;
import com.example.starter.web.dto.GrantResponse;
import com.example.starter.web.dto.RecordResponse;
import com.example.starter.web.dto.RevokeRequest;
import com.example.starter.web.dto.WriteRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地数据授权 API：授权、撤回、记录写入与查询。
 */
@RestController
@RequestMapping("/api")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * 授权：首次生成第 1 代；有效时重复授权返回原 epoch；撤回后重新授权生成下一代。
     */
    @PostMapping("/consents")
    public GrantResponse grant(@Valid @RequestBody GrantRequest request) {
        return consentService.grant(request.requestId(), request.subjectKey(), parsePurpose(request.purpose()));
    }

    /**
     * 按代次撤回授权；撤回提交后旧代查询返回 410、写入被拒绝。
     */
    @PostMapping("/consents/revoke")
    public GrantResponse revoke(@Valid @RequestBody RevokeRequest request) {
        return consentService.revoke(request.requestId(), request.subjectKey(),
                parsePurpose(request.purpose()), request.epoch());
    }

    /**
     * 写入记录到当前有效代次；同代同 key 同 payload 幂等返回原记录，不同 payload 返回 409。
     */
    @PostMapping("/records")
    public RecordResponse write(@Valid @RequestBody WriteRequest request) {
        return consentService.write(request.requestId(), request.subjectKey(),
                parsePurpose(request.purpose()), request.recordKey(), request.payload());
    }

    /**
     * 查询当前有效代次下的记录；已撤回返回 410，不存在返回 404。
     */
    @GetMapping("/records")
    public RecordResponse read(@RequestParam String subjectKey,
                               @RequestParam String purpose,
                               @RequestParam String recordKey) {
        return consentService.read(subjectKey, parsePurpose(purpose), recordKey);
    }

    private Purpose parsePurpose(String value) {
        Purpose purpose = Purpose.from(value);
        if (purpose == null) {
            throw ApiException.badRequest("用途非法，仅支持 RESEARCH 或 PERSONALIZATION: " + value);
        }
        return purpose;
    }
}
