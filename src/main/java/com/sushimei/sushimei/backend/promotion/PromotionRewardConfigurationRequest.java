package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PromotionRewardConfigurationRequest(
        @NotNull @Min(1) Integer rewardOrdinal,
        @Min(1) Long menuItemId,
        List<@NotNull @Valid MenuQuoteGroupRequest> groups,
        List<@NotNull @Min(1) Long> omittedComponentIds,
        String note
) {
    public PromotionRewardConfigurationRequest {
        groups = List.copyOf(groups == null ? List.of() : groups);
        omittedComponentIds = List.copyOf(omittedComponentIds == null ? List.of() : omittedComponentIds);
    }

    public PromotionRewardConfigurationRequest(Integer rewardOrdinal,
                                               Long menuItemId,
                                               List<MenuQuoteGroupRequest> groups) {
        this(rewardOrdinal, menuItemId, groups, List.of(), null);
    }

    public PromotionRewardConfigurationRequest(Integer rewardOrdinal,
                                               List<MenuQuoteGroupRequest> groups) {
        this(rewardOrdinal, null, groups, List.of(), null);
    }

}
