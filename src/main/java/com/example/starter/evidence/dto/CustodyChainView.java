package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 封签版本 + 全部交接记录 + 全部借出记录 +
 * 全部封条核验记录 + 全部检验保管链事件（均按发生顺序，只追加、不可变）。
 *
 * @param evidence     证物当前视图
 * @param sealVersions 封签版本历史，按版本号升序（入库原封签 + 每次检验完成的新封签）
 * @param transfers    历史交接记录，只追加
 * @param loans        历史借出记录，只追加
 * @param inspections  历史封条核验记录（含归还时的封条核验），只追加
 * @param examinations 历史检验链事件（预约/开始/完成/取消），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<SealVersionView> sealVersions,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<ExaminationEventView> examinations) {
}
