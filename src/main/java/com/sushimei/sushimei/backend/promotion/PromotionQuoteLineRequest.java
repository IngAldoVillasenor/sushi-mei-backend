package com.sushimei.sushimei.backend.promotion;

import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record PromotionQuoteLineRequest(
        @NotBlank String lineKey,
        @NotNull @Min(1) Long menuItemId,
        @NotNull @Min(1) Integer quantity,
        List<@NotNull @Valid MenuQuoteGroupRequest> groups,
        List<@NotNull @Valid PromotionRewardConfigurationRequest> rewardConfigurations,
        List<@NotNull @Min(1) Long> omittedComponentIds,
        String note
) {
    public PromotionQuoteLineRequest {
        groups = List.copyOf(groups == null ? List.of() : groups);
        rewardConfigurations = List.copyOf(rewardConfigurations == null ? List.of() : rewardConfigurations);
        omittedComponentIds = List.copyOf(omittedComponentIds == null ? List.of() : omittedComponentIds);
    }

    /** Source-compatible constructor for clients without line customizations. */
    public PromotionQuoteLineRequest(String lineKey,
                                     Long menuItemId,
                                     Integer quantity,
                                     List<MenuQuoteGroupRequest> groups,
                                     List<PromotionRewardConfigurationRequest> rewardConfigurations) {
        this(lineKey, menuItemId, quantity, groups, rewardConfigurations, List.of(), null);
    }
}
