package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 整体替换草稿占用清单请求，expectedVersion 必须与当前版本一致。
 * hazardCars 与占用清单一并整体替换（可空表示清空危险品编组），其 trainNo 必须出现在 occupancies 中。
 * operator 为操作者标识，缺省 system，参与幂等指纹。
 */
public record UpdateOccupanciesRequest(
        @NotBlank String requestKey,
        String operator,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 1, max = 30) List<@Valid OccupancyRequest> occupancies,
        @Size(max = 100) List<@Valid HazardCarRequest> hazardCars) {

    /** 无操作者、无危险品编组的便捷构造器（操作者缺省 system，危险品编组清空）。 */
    public UpdateOccupanciesRequest(String requestKey, Integer expectedVersion,
                                    List<OccupancyRequest> occupancies) {
        this(requestKey, null, expectedVersion, occupancies, null);
    }
}
