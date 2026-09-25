package com.example.starter.firmware.service;

import com.example.starter.firmware.api.RegisterApproverRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FreezeApproverRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 冻结紧急例外确认人登记与查询。
 */
@Service
public class ApproverService {

    private final FreezeApproverRepository approverRepository;
    private final IdempotencyService idempotency;

    public ApproverService(FreezeApproverRepository approverRepository, IdempotencyService idempotency) {
        this.approverRepository = approverRepository;
        this.idempotency = idempotency;
    }

    public String register(RegisterApproverRequest request) {
        String fingerprint = String.join("|", "approver.register", request.approverId());
        return idempotency.execute(request.requestId(), "approver.register", fingerprint, () -> {
            try {
                approverRepository.insert(request.approverId());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("APPROVER_EXISTS", "确认人已登记: " + request.approverId());
            }
            return request.approverId();
        }, String.class);
    }

    public List<String> list() {
        return approverRepository.findAll();
    }
}
