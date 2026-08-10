package com.sushimei.sushimei.backend.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplaceMenuItemTagsRequest(
        @NotNull @Min(0) Long itemVersion,
        @NotNull List<@NotNull @Min(1) Long> tagIds
) {
    public ReplaceMenuItemTagsRequest {
        tagIds = tagIds == null ? null : List.copyOf(tagIds);
    }
}
