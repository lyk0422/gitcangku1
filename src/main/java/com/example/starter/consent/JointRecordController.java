package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.JointRecordConfirmRequest;
import com.example.starter.consent.dto.JointRecordCreateRequest;
import com.example.starter.consent.dto.JointRecordReadResponse;
import com.example.starter.consent.dto.JointRecordResponse;
import com.example.starter.consent.dto.JointRecordReviseRequest;

import jakarta.validation.Valid;

/**
 * 共同拥有记录 API：新增提案、内容修订、双方确认与读取。
 * 所有写操作要求非空 X-Actor-Id 操作者标识；确认时该标识必须等于声明主体。
 */
@Validated
@RestController
@RequestMapping("/api/v1/joint-records")
public class JointRecordController {

    private final JointRecordService jointRecordService;

    public JointRecordController(JointRecordService jointRecordService) {
        this.jointRecordService = jointRecordService;
    }

    @PostMapping
    public JointRecordResponse create(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @Valid @RequestBody JointRecordCreateRequest request) {
        return jointRecordService.create(actorId, request);
    }

    @PostMapping("/{recordKey}/revisions")
    public JointRecordResponse revise(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @PathVariable String recordKey,
            @Valid @RequestBody JointRecordReviseRequest request) {
        return jointRecordService.revise(actorId, recordKey, request);
    }

    @PostMapping("/{recordKey}/confirmations")
    public JointRecordResponse confirm(
            @RequestHeader(name = "X-Actor-Id", required = false) String actorId,
            @PathVariable String recordKey,
            @Valid @RequestBody JointRecordConfirmRequest request) {
        return jointRecordService.confirm(actorId, recordKey, request);
    }

    @GetMapping("/{recordKey}")
    public JointRecordReadResponse read(@PathVariable String recordKey) {
        return jointRecordService.read(recordKey);
    }
}
