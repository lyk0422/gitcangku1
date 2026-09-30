package com.example.starter.salinity.service;

import com.example.starter.salinity.command.AddErratumCommand;
import com.example.starter.salinity.command.AdoptSampleCommand;
import com.example.starter.salinity.command.SealVoyageCommand;
import com.example.starter.salinity.command.SubmitSampleCommand;
import com.example.starter.salinity.view.SampleView;
import com.example.starter.salinity.view.VoyageDataView;

/**
 * 盐度采样业务服务：采样提交、质控替换、航次封存、勘误与查询。
 */
public interface SalinityService {

    /** 提交样本（同参重放返回原样本）。 */
    SampleView submit(SubmitSampleCommand command);

    /** 质控采纳指定样本为站位深度唯一版本。 */
    SampleView adopt(AdoptSampleCommand command);

    /** 封存航次，固化各站位 0、50、100 米剖面；失败不锁定航次。 */
    void seal(SealVoyageCommand command);

    /** 封存后追加勘误。 */
    void addErratum(AddErratumCommand command);

    /** 查询航次全部数据：原始样本、采纳版本、冻结剖面、勘误。 */
    VoyageDataView query(String voyageCode);
}
