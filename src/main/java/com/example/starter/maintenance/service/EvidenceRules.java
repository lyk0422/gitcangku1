package com.example.starter.maintenance.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.EvidenceItemResult;
import com.example.starter.maintenance.domain.EvidenceMaterial;
import com.example.starter.maintenance.domain.EvidencePackage;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MaterialBatch;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.domain.TemplateItem;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EvidenceRepository;

/**
 * 证据包齐备性诊断规则：计算当前周期证据包相对当前模板版本的缺失/失败项与材料撤销情况，
 * 供完成保养门禁（422 明细）与诊断查询共用。所有读取均不改变状态。
 */
@Component
public class EvidenceRules {

    private final EquipmentRepository equipmentRepository;
    private final EvidenceRepository evidenceRepository;

    public EvidenceRules(EquipmentRepository equipmentRepository, EvidenceRepository evidenceRepository) {
        this.equipmentRepository = equipmentRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * 计算设备当前周期的证据诊断。调用方应已取得设备行锁（写路径）；查询路径只读调用。
     */
    public EvidenceDiagnostics diagnose(Equipment equipment) {
        String equipmentId = equipment.equipmentId();
        long cycleNo = equipment.currentCycleNo();

        Optional<Reading> latest = equipmentRepository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = equipmentRepository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        boolean overdue = runMinutes >= equipment.maintenancePeriodMinutes()
                * EvidenceTxService.OVERDUE_MULTIPLE;

        String templateId = equipment.templateId();
        if (templateId == null) {
            // 未分配模板的设备不启用证据包门禁：无证据包也视为"无缺失"，由调用方决定流程。
            return new EvidenceDiagnostics(null, null, runMinutes, overdue, 0, 0,
                    List.of(), List.of(), List.of(), 0);
        }

        Integer currentTemplateVersion = evidenceRepository.findTemplateCurrentVersion(templateId).orElse(null);
        Optional<EvidencePackage> latestPackage = evidenceRepository.findLatestPackage(equipmentId, cycleNo);

        if (latestPackage.isEmpty()) {
            // 当前模板的全部必检项都缺失
            List<TemplateItem> currentItems = currentTemplateVersion == null
                    ? List.of()
                    : evidenceRepository.listTemplateItems(templateId, currentTemplateVersion);
            List<String> missing = currentItems.stream()
                    .filter(TemplateItem::required)
                    .map(TemplateItem::itemCode)
                    .toList();
            return new EvidenceDiagnostics(null, currentTemplateVersion, runMinutes, overdue,
                    missing.size(), 0, missing, List.of(), List.of(), 0);
        }

        EvidencePackage pkg = latestPackage.get();
        List<TemplateItem> currentItems = currentTemplateVersion == null
                ? List.of()
                : evidenceRepository.listTemplateItems(templateId, currentTemplateVersion);
        List<TemplateItem> requiredItems = currentItems.stream()
                .filter(TemplateItem::required)
                .toList();

        List<EvidenceItemResult> results = evidenceRepository.listItemResults(pkg.packageId());
        Map<String, String> resultByCode = new HashMap<>();
        List<String> failed = new ArrayList<>();
        for (EvidenceItemResult result : results) {
            resultByCode.put(result.itemCode(), result.result());
            if ("FAIL".equals(result.result())) {
                failed.add(result.itemCode());
            }
        }
        // 必检项必须恰有一个 PASS 结果：有 FAIL 结果的项单列 FAILED_ITEM，完全无结果才计入缺失
        List<String> missing = new ArrayList<>();
        for (TemplateItem required : requiredItems) {
            if (!"PASS".equals(resultByCode.get(required.itemCode()))
                    && !"FAIL".equals(resultByCode.get(required.itemCode()))) {
                missing.add(required.itemCode());
            }
        }

        List<EvidenceMaterial> materials = evidenceRepository.listEvidenceMaterials(pkg.packageId());
        List<String> revoked = new ArrayList<>();
        for (EvidenceMaterial material : materials) {
            Optional<MaterialBatch> batch = evidenceRepository.findMaterialBatch(material.batchCode());
            if (batch.isEmpty() || "REVOKED".equals(batch.get().status())) {
                revoked.add(material.batchCode());
            }
        }

        int passedRequired = (int) requiredItems.stream()
                .filter(item -> "PASS".equals(resultByCode.get(item.itemCode())))
                .count();
        return new EvidenceDiagnostics(pkg, currentTemplateVersion, runMinutes, overdue,
                requiredItems.size(), passedRequired, List.copyOf(missing), List.copyOf(failed),
                List.copyOf(revoked), materials.size());
    }
}
