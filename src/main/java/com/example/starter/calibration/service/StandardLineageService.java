package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.model.StandardVersionStatus;
import com.example.starter.calibration.repo.DomainStateRepository;
import com.example.starter.calibration.repo.StandardRepository;
import com.example.starter.calibration.repo.StandardVersionRepository;

/**
 * 标准器血缘服务：创建标准器与标准器版本。
 * 血缘必须无环，子级有效窗口不得超出父级窗口；新增血缘递增领域版本并与失效激活按提交顺序串行化。
 */
@Service
public class StandardLineageService {

    /** 血缘向上遍历时的最大代数，防御异常数据成环。 */
    private static final int MAX_LINEAGE_DEPTH = 10_000;

    private final StandardRepository standards;
    private final StandardVersionRepository versions;
    private final DomainStateRepository domainState;

    public StandardLineageService(StandardRepository standards,
                                  StandardVersionRepository versions,
                                  DomainStateRepository domainState) {
        this.standards = standards;
        this.versions = versions;
        this.domainState = domainState;
    }

    /**
     * 创建标准器；standardId 重复返回 409。
     */
    @Transactional
    public long createStandard(String standardId, String name) {
        String id = Inputs.requireText(standardId, "standardId");
        String standardName = Inputs.requireText(name, "name");
        try {
            return standards.insert(id, standardName, Instant.now());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_STANDARD_ID", "标准器已存在: " + id);
        }
    }

    /**
     * 创建标准器版本。parentVersionKey 为空表示根版本；非空时：
     * 父版本必须存在、血缘无环、子级窗口必须落在父级窗口内。
     */
    @Transactional
    public StandardVersion createVersion(String versionKey, String standardId, String parentVersionKey,
                                         String validFromText, String validToText, String certificateNo) {
        String key = Inputs.requireText(versionKey, "versionKey");
        String stdId = Inputs.requireText(standardId, "standardId");
        Instant validFrom = Inputs.requireInstant(validFromText, "validFrom");
        Instant validTo = Inputs.requireInstant(validToText, "validTo");
        String certNo = Inputs.requireText(certificateNo, "certificateNo");
        if (!validFrom.isBefore(validTo)) {
            throw ApiException.badRequest("validFrom 必须早于 validTo");
        }
        if (standards.findByStandardId(stdId).isEmpty()) {
            throw ApiException.notFound("标准器不存在: " + stdId);
        }
        if (versions.findByVersionKey(key).isPresent()) {
            throw ApiException.conflict("DUPLICATE_VERSION_KEY", "标准器版本已存在: " + key);
        }

        // 先取领域版本行锁：与失效激活并发时按提交顺序串行化，杜绝激活后再挂入新子孙。
        domainState.currentVersionForUpdate();

        Long parentId = null;
        if (parentVersionKey != null && !parentVersionKey.isBlank()) {
            StandardVersion parent = versions.findByVersionKey(parentVersionKey.trim())
                    .orElseThrow(() -> ApiException.notFound("上级标准器版本不存在: " + parentVersionKey));
            if (parent.validFrom().isAfter(validFrom) || parent.validTo().isBefore(validTo)) {
                throw ApiException.unprocessableLineage(
                        "子级有效窗口不得超出父级窗口: parent=[" + parent.validFrom() + ", " + parent.validTo()
                                + ") child=[" + validFrom + ", " + validTo + ")");
            }
            parentId = parent.id();
            assertAcyclic(parent);
        }

        StandardVersion version = new StandardVersion(
                0L, key, stdId, parentId, validFrom, validTo, certNo,
                StandardVersionStatus.VALID, Instant.now());
        try {
            versions.insert(version);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_VERSION_KEY", "标准器版本已存在: " + key);
        }
        domainState.increment();
        return versions.findByVersionKey(key).orElseThrow();
    }

    /**
     * 沿父指针向上遍历，确认血缘无环且能正常终止于根版本。
     */
    private void assertAcyclic(StandardVersion start) {
        Set<Long> seen = new HashSet<>();
        Long parentId = start.parentVersionId();
        int hops = 0;
        while (parentId != null) {
            if (!seen.add(parentId)) {
                throw ApiException.unprocessableLineage("标准器血缘存在环: versionId=" + parentId);
            }
            if (++hops > MAX_LINEAGE_DEPTH) {
                throw ApiException.unprocessableLineage("标准器血缘深度超过上限，疑似成环");
            }
            long currentId = parentId;
            StandardVersion ancestor = versions.findById(currentId)
                    .orElseThrow(() -> ApiException.unprocessableLineage(
                            "上级标准器版本缺失: versionId=" + currentId));
            parentId = ancestor.parentVersionId();
        }
    }
}
