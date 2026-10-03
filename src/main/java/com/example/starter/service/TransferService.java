package com.example.starter.service;

import com.example.starter.domain.TagRow;
import com.example.starter.domain.TagStatus;
import com.example.starter.domain.TransferRow;
import com.example.starter.domain.TransferStatus;
import com.example.starter.repo.TagRepository;
import com.example.starter.repo.TransferRepository;
import com.example.starter.web.dto.ApiDtos.TransferConfirmRequest;
import com.example.starter.web.dto.ApiDtos.TransferInitRequest;
import com.example.starter.web.dto.ApiDtos.TransferResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站点间交接：当前持有站点发起、目标站点确认后持有权才转移；
 * 未确认可取消（持有权从未离开原站点），已确认不得取消。
 */
@Service
public class TransferService {

    private static final String OP_INIT = "TRANSFER_INIT";
    private static final String OP_CONFIRM = "TRANSFER_CONFIRM";
    private static final String OP_CANCEL = "TRANSFER_CANCEL";

    private final TagRepository tagRepository;
    private final TransferRepository transferRepository;
    private final IdempotencyExecutor idempotency;
    private final TagService tagService;

    public TransferService(TagRepository tagRepository, TransferRepository transferRepository,
            IdempotencyExecutor idempotency, TagService tagService) {
        this.tagRepository = tagRepository;
        this.transferRepository = transferRepository;
        this.idempotency = idempotency;
        this.tagService = tagService;
    }

    @Transactional
    public TransferResponse initiate(TransferInitRequest request) {
        return idempotency.execute(OP_INIT, request.requestId(), request, TransferResponse.class,
                () -> doInitiate(request));
    }

    private TransferResponse doInitiate(TransferInitRequest request) {
        TagRow tag = tagService.requireTagForUpdate(request.tagCode());
        if (tag.status() == TagStatus.ATTACHED) {
            throw BusinessException.conflict("TAG_STILL_ATTACHED",
                    "标签未摘除，不能发起交接: " + request.tagCode(), request.tagCode(), OP_INIT);
        }
        if (!tag.holderStationCode().equals(request.fromStationCode())) {
            throw BusinessException.conflict("NOT_HOLDER_STATION",
                    "只能由当前持有站点发起交接，当前持有站点: " + tag.holderStationCode(),
                    request.tagCode(), OP_INIT);
        }
        if (request.fromStationCode().equals(request.toStationCode())) {
            throw BusinessException.unprocessable("SAME_STATION",
                    "目标站点不能与发起站点相同", request.tagCode(), OP_INIT);
        }
        if (!tagRepository.stationExists(request.toStationCode())) {
            throw BusinessException.unprocessable("STATION_NOT_FOUND",
                    "目标站点不存在: " + request.toStationCode(), request.tagCode(), OP_INIT);
        }
        if (transferRepository.findPendingByTag(request.tagCode()).isPresent()) {
            throw BusinessException.conflict("TRANSFER_PENDING",
                    "标签已存在待确认交接: " + request.tagCode(), request.tagCode(), OP_INIT);
        }
        return toResponse(transferRepository.insert(request.tagCode(), request.fromStationCode(),
                request.toStationCode(), request.initiatedAt(), request.requestId()));
    }

    /** 目标站点确认：持有权在此刻转移；确认时刻不得早于发起时刻。 */
    @Transactional
    public TransferResponse confirm(long transferId, TransferConfirmRequest request) {
        // 先锁标签行，与摘除/附着保持同一串行化点；锁定后重读交接单，保证状态判定基于已提交数据
        TransferRow probe = requireTransfer(transferId, OP_CONFIRM);
        tagService.requireTagForUpdate(probe.tagCode());
        TransferRow transfer = requireTransfer(transferId, OP_CONFIRM);
        if (transfer.status() != TransferStatus.PENDING) {
            throw BusinessException.conflict("TRANSFER_NOT_PENDING",
                    "交接单不是待确认状态: " + transfer.status(), transfer.tagCode(), OP_CONFIRM);
        }
        if (!transfer.toStationCode().equals(request.stationCode())) {
            throw BusinessException.unprocessable("TARGET_STATION_MISMATCH",
                    "确认站点不是目标站点: " + request.stationCode(), transfer.tagCode(), OP_CONFIRM);
        }
        if (request.confirmedAt().isBefore(transfer.initiatedAt())) {
            throw BusinessException.unprocessable("TIME_REGRESSION",
                    "确认时刻早于发起时刻: " + request.confirmedAt(), transfer.tagCode(), OP_CONFIRM);
        }
        transferRepository.updateStatus(transferId, TransferStatus.CONFIRMED, request.confirmedAt());
        tagRepository.updateHolder(transfer.tagCode(), transfer.toStationCode());
        return toResponse(new TransferRow(transfer.id(), transfer.tagCode(), transfer.fromStationCode(),
                transfer.toStationCode(), TransferStatus.CONFIRMED, transfer.initiatedAt(),
                request.confirmedAt(), transfer.requestId()));
    }

    /** 取消仅允许未确认交接；持有权从未转移，取消即恢复原持有权。 */
    @Transactional
    public TransferResponse cancel(long transferId) {
        TransferRow probe = requireTransfer(transferId, OP_CANCEL);
        tagService.requireTagForUpdate(probe.tagCode());
        TransferRow transfer = requireTransfer(transferId, OP_CANCEL);
        if (transfer.status() == TransferStatus.CONFIRMED) {
            throw BusinessException.conflict("TRANSFER_ALREADY_CONFIRMED",
                    "已确认交接不得取消: " + transferId, transfer.tagCode(), OP_CANCEL);
        }
        if (transfer.status() != TransferStatus.PENDING) {
            throw BusinessException.conflict("TRANSFER_NOT_PENDING",
                    "交接单不是待确认状态: " + transfer.status(), transfer.tagCode(), OP_CANCEL);
        }
        transferRepository.updateStatus(transferId, TransferStatus.CANCELLED, null);
        return toResponse(new TransferRow(transfer.id(), transfer.tagCode(), transfer.fromStationCode(),
                transfer.toStationCode(), TransferStatus.CANCELLED, transfer.initiatedAt(),
                null, transfer.requestId()));
    }

    private TransferRow requireTransfer(long transferId, String operation) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "交接单不存在: " + transferId, null, null, operation));
    }

    static TransferResponse toResponse(TransferRow row) {
        return new TransferResponse(row.id(), row.tagCode(), row.fromStationCode(), row.toStationCode(),
                row.status().name(), row.initiatedAt(), row.confirmedAt());
    }
}