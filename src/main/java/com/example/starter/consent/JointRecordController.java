package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.JointConfirmRequest;
import com.example.starter.consent.dto.JointCreateRequest;
import com.example.starter.consent.dto.JointProposalResponse;
import com.example.starter.consent.dto.JointRecordValueResponse;
import com.example.starter.consent.dto.JointReviseRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 共同研究记录 API：两个主体共同提供的研究记录，创建、确认、修订与含值读取。
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
    public JointProposalResponse create(@Valid @RequestBody JointCreateRequest request) {
        return jointRecordService.create(request);
    }

    @PostMapping("/{recordKey}/confirmations")
    public JointProposalResponse confirm(@PathVariable @NotBlank String recordKey,
                                         @RequestHeader("X-Actor-Id") @NotBlank String actorId,
                                         @Valid @RequestBody JointConfirmRequest request) {
        return jointRecordService.confirm(recordKey, actorId, request);
    }

    @PostMapping("/{recordKey}/revisions")
    public JointProposalResponse revise(@PathVariable @NotBlank String recordKey,
                                        @Valid @RequestBody JointReviseRequest request) {
        return jointRecordService.revise(recordKey, request);
    }

    @GetMapping("/{recordKey}")
    public JointRecordValueResponse read(@PathVariable @NotBlank String recordKey) {
        return jointRecordService.read(recordKey);
    }
}
