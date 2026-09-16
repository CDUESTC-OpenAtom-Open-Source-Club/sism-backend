package com.sism.strategy.interfaces.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class BatchDistributeIndicatorsRequest {

    @Valid
    @NotEmpty(message = "Indicators cannot be empty")
    private List<Item> indicators = new ArrayList<>();

    @Data
    public static class Item {
        private String clientRequestId;
        private Long indicatorId;

        private String indicatorDesc;
        private String type;
        private String indicatorType;
        private String type1;

        private Long taskId;
        private Long parentIndicatorId;

        private Long ownerOrgId;

        @NotNull(message = "Target organization is required")
        private Long targetOrgId;

        @DecimalMin(value = "0.0001", message = "Weight percent must be positive")
        private BigDecimal weightPercent;

        private Integer sortOrder;
        private String remark;
        private Integer progress;
        private String customDesc;
    }
}
