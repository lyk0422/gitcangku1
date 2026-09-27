package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.LabelRevision;
import com.example.starter.firmware.repo.LabelDictRepository;
import com.example.starter.firmware.repo.LabelRevisionRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 标签字典与修订事件。所有写方法都必须在调用方开启的事务内执行（与业务变更、幂等记录原子提交）：
 * 先锁 label_dict_meta 单行，再登记缺失标签，仅当实际引入新标签时推进版本；
 * 设备登记仅在引入新标签时留修订事件，批量标签修改始终留一条修订事件（版本增量可为0）。
 * 统一“先 meta 行锁、后设备行锁”的加锁顺序，避免与发布单快照解析交叉死锁。
 */
@Service
public class LabelDictService {

    /**
     * 一次字典合并的结果：实际新标签数与合并后的字典版本。
     */
    public record MergeOutcome(int newTagCount, long versionAfter) {
    }

    private final LabelDictRepository dictRepository;
    private final LabelRevisionRepository revisionRepository;
    private final Clock clock;

    public LabelDictService(LabelDictRepository dictRepository, LabelRevisionRepository revisionRepository,
                            Clock clock) {
        this.dictRepository = dictRepository;
        this.revisionRepository = revisionRepository;
        this.clock = clock;
    }

    /**
     * 锁定字典版本行并合并一批规范化标签；不写修订事件，由调用方决定是否记录。
     */
    public MergeOutcome mergeTagsLocked(List<String> normalizedTags) {
        long versionBefore = dictRepository.currentVersionForUpdate();
        int inserted = dictRepository.insertMissingTags(normalizedTags);
        if (inserted > 0) {
            dictRepository.bumpVersion();
        }
        return new MergeOutcome(inserted, versionBefore + (inserted > 0 ? 1 : 0));
    }

    /**
     * 设备登记合并标签：仅当引入新标签时记录 DEVICE_REGISTER 修订事件。
     * 调用方必须已通过 {@link #mergeTagsLocked} 持锁并得到合并结果。
     */
    public void recordDeviceRegistrationIfChanged(String operator, MergeOutcome outcome) {
        if (outcome.newTagCount() > 0) {
            revisionRepository.insert(LabelRevisionRepository.EVENT_DEVICE_REGISTER, operator,
                    1, outcome.newTagCount(), outcome.versionAfter(), Instant.now(clock).toString());
        }
    }

    /**
     * 批量标签修改始终记录一条 LABEL_BATCH_UPDATE 修订事件（无新标签时版本增量为0）。
     */
    public void recordBatchUpdate(String operator, int deviceCount, MergeOutcome outcome) {
        revisionRepository.insert(LabelRevisionRepository.EVENT_LABEL_BATCH_UPDATE, operator,
                deviceCount, outcome.newTagCount(), outcome.versionAfter(), Instant.now(clock).toString());
    }

    /**
     * 仅锁定字典版本行并返回当前版本（不合并标签）；发布单快照解析用，
     * 与标签修订串行化，保证解析期间字典版本与设备标签集合都是一致快照。
     * 调用方必须在事务内。
     */
    public long lockAndCurrentVersion() {
        return dictRepository.currentVersionForUpdate();
    }

    public long currentVersion() {
        return dictRepository.currentVersion();
    }

    public List<String> allTags() {
        return dictRepository.findAllTags();
    }

    public List<LabelRevision> revisions() {
        return revisionRepository.findAll();
    }

    public List<LabelRevision> revisionsBetween(String fromUtc, String toUtc) {
        return revisionRepository.findBetweenUtc(fromUtc, toUtc);
    }
}
