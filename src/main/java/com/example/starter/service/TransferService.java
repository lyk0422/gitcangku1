package com.example.starter.service;

import com.example.starter.domain.Transfer;
import com.example.starter.domain.TransferStatus;
import com.example.starter.repo.AttachmentRepository;
import com.example.starter.repo.TagOwnershipRepository;
import com.example.starter.repo.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站点间交接：只能由当前持有站点发起、目标站点确认；
 * 取消仅允许待确认状态，已确认交接不得直接取消；失败时不留下半个所有权转移。
 */
@Service
public class TransferService {

    /**
     * 交接单视图。
     *
     * @param id 交接单主键
     * @param tagCode 标签编号
     * @param fromStation 发起站点
     * @param toStation 接收站点
     * @param status 状态：PENDING / CONFIRMED / CANCELLED
     * @param initiatedAt 发起时刻（UTC）
     * @param confirmedAt 确认时刻（UTC）；未确认为 null
     * @param cancelledAt 取消时刻（UTC）；未取消为 null
     */
    public record TransferView(long id, String tagCode, String fromStation, String toStation,
            String status, Instant initiatedAt, Instant confirmedAt, Instant cancelledAt) {

        static TransferView of(Transfer t) {
            return new TransferView(t.id(), t.tagCode(), t.fromStation(), t.toStation(),
                    t.status().name(), t.initiatedAt(), t.confirmedAt(), t.cancelledAt());
        }
    }

    private final TagOwnershipRepository ownerships;
    private final AttachmentRepository attachments;
    private final TransferRepository transfers;
    private final Idempotency idempotency;
    private final Clock clock;

    public TransferService(TagOwnershipRepository ownerships, AttachmentRepository attachments,
            TransferRepository transfers, Idempotency idempotency, Clock clock) {
        this.ownerships = ownerships;
        this.attachments = attachments;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 发起交接：仅当前持有站点可发起，要求标签已摘除且无其他待确认交接。
     * 使用 requestId 幂等：同参重放，异参 409。
     */
    @Transactional
    public TransferView initiate(String requestId, String tagCode, String fromStation,
            String toStation, Instant initiatedAt) {
        Instant at = initiatedAt != null ? initiatedAt : clock.instant();
        String fingerprint = String.join("|", tagCode, fromStation, toStation);
        return idempotency.run(requestId, "TRANSFER", fingerprint, TransferView.class, () -> {
            var ownership = ownerships.findForUpdate(tagCode).orElseThrow(() ->
                    BusinessException.notFound("TAG_NOT_FOUND", "标签未登记: " + tagCode)
                            .operation("TRANSFER").tagCode(tagCode).stationCode(fromStation).build());
            if (!ownership.stationCode().equals(fromStation)) {
                throw BusinessException.conflict("NOT_CURRENT_HOLDER",
                        "只有当前持有站点 " + ownership.stationCode() + " 能发起交接")
                        .operation("TRANSFER").tagCode(tagCode).stationCode(fromStation).build();
            }
            attachments.findOpenByTag(tagCode).ifPresent(open -> {
                throw BusinessException.conflict("TAG_NOT_DETACHED",
                        "标签尚未摘除，仍附着在动物 " + open.animalCode())
                        .operation("TRANSFER").tagCode(tagCode).stationCode(fromStation).build();
            });
            transfers.findPendingByTag(tagCode).ifPresent(pending -> {
                throw BusinessException.conflict("TRANSFER_ALREADY_PENDING",
                        "标签已存在待确认交接单 " + pending.id())
                        .operation("TRANSFER").tagCode(tagCode).stationCode(fromStation).build();
            });
            long id = transfers.insert(tagCode, fromStation, toStation, at, requestId);
            return new TransferView(id, tagCode, fromStation, toStation,
                    TransferStatus.PENDING.name(), at, null, null);
        });
    }

    /**
     * 目标站点确认：确认后持有权转移给接收站点；与并发摘除/确认按所有权行锁的提交顺序判定。
     */
    @Transactional
    public TransferView confirm(long transferId, String stationCode, Instant confirmedAt) {
        transfers.findById(transferId).orElseThrow(() ->
                BusinessException.notFound("TRANSFER_NOT_FOUND", "交接单不存在: " + transferId)
                        .operation("CONFIRM").stationCode(stationCode).build());
        var transfer = transfers.findByIdForUpdate(transferId).orElseThrow();
        var ownership = ownerships.findForUpdate(transfer.tagCode()).orElseThrow(() ->
                BusinessException.notFound("TAG_NOT_FOUND", "标签未登记: " + transfer.tagCode())
                        .operation("CONFIRM").tagCode(transfer.tagCode())
                        .stationCode(stationCode).build());
        if (transfer.status() != TransferStatus.PENDING) {
            throw BusinessException.conflict("TRANSFER_NOT_PENDING",
                    "交接单当前状态为 " + transfer.status() + "，不能确认")
                    .operation("CONFIRM").tagCode(transfer.tagCode())
                    .stationCode(stationCode).build();
        }
        if (!transfer.toStation().equals(stationCode)) {
            throw BusinessException.conflict("TRANSFER_TARGET_MISMATCH",
                    "只有目标站点 " + transfer.toStation() + " 能确认交接")
                    .operation("CONFIRM").tagCode(transfer.tagCode())
                    .stationCode(stationCode).build();
        }
        if (!ownership.stationCode().equals(transfer.fromStation())) {
            throw BusinessException.conflict("OWNERSHIP_CHANGED",
                    "标签持有权已不在发起站点，交接失效")
                    .operation("CONFIRM").tagCode(transfer.tagCode())
                    .stationCode(stationCode).build();
        }
        Instant at = confirmedAt != null ? confirmedAt : clock.instant();
        if (at.isBefore(transfer.initiatedAt())) {
            throw BusinessException.unprocessable("CONFIRM_TIME_REGRESSION",
                    "确认时刻早于发起时刻 " + transfer.initiatedAt())
                    .operation("CONFIRM").tagCode(transfer.tagCode())
                    .stationCode(stationCode).build();
        }
        transfers.markConfirmed(transferId, at);
        ownerships.updateStation(transfer.tagCode(), transfer.toStation());
        return TransferView.of(transfers.findById(transferId).orElseThrow());
    }

    /**
     * 发起站点取消：仅待确认状态可取消，已确认交接不得直接取消；取消后持有权保持原站点。
     */
    @Transactional
    public TransferView cancel(long transferId, String stationCode, Instant cancelledAt) {
        transfers.findById(transferId).orElseThrow(() ->
                BusinessException.notFound("TRANSFER_NOT_FOUND", "交接单不存在: " + transferId)
                        .operation("CANCEL").stationCode(stationCode).build());
        var transfer = transfers.findByIdForUpdate(transferId).orElseThrow();
        ownerships.findForUpdate(transfer.tagCode()).orElseThrow(() ->
                BusinessException.notFound("TAG_NOT_FOUND", "标签未登记: " + transfer.tagCode())
                        .operation("CANCEL").tagCode(transfer.tagCode())
                        .stationCode(stationCode).build());
        if (transfer.status() != TransferStatus.PENDING) {
            throw BusinessException.conflict("TRANSFER_NOT_CANCELLABLE",
                    "交接单当前状态为 " + transfer.status() + "，不能取消")
                    .operation("CANCEL").tagCode(transfer.tagCode())
                    .stationCode(stationCode).build();
        }
        if (!transfer.fromStation().equals(stationCode)) {
            throw BusinessException.conflict("NOT_CURRENT_HOLDER",
                    "只有发起站点 " + transfer.fromStation() + " 能取消交接")
                    .operation("CANCEL").tagCode(transfer.tagCode())
                    .stationCode(stationCode).build();
        }
        transfers.markCancelled(transferId, cancelledAt != null ? cancelledAt : clock.instant());
        return TransferView.of(transfers.findById(transferId).orElseThrow());
    }
}