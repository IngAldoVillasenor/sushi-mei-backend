package com.sushimei.sushimei.backend.pos;

import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest;
import com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionRequest;
import com.sushimei.sushimei.backend.promotion.PromotionQuoteLineRequest;
import com.sushimei.sushimei.backend.promotion.PromotionRewardConfigurationRequest;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Canonical, price-free digest of a manual order request used only for idempotency safety. */
@Component
public class ManualPosOrderFingerprint {

    private static final int V22_EXTENSION_MAGIC = 0x56323201;

    public String fingerprint(com.sushimei.sushimei.backend.entity.OrderFulfillmentType fulfillmentType,
                              com.sushimei.sushimei.backend.entity.OrderPaymentMethod paymentMethod,
                              String deliveryAddress,
                              String pickupName,
                              BigDecimal cashDenomination,
                              List<PromotionQuoteLineRequest> lines,
                              List<NormalizedManualPricedLine> manualLines) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, fulfillmentType == null ? null : fulfillmentType.name());
            writeString(output, paymentMethod == null ? null : paymentMethod.name());
            writeString(output, deliveryAddress);
            writeString(output, pickupName);
            writeString(output, cashDenomination == null ? null : cashDenomination.toPlainString());
            writeLines(output, lines);
            // Keep pre-V22 byte-for-byte compatibility when every new field is absent.
            if (hasV22Extension(lines, manualLines)) {
                output.writeInt(V22_EXTENSION_MAGIC);
                writeNestedAndRewardCustomizations(output, lines);
                writeManualLines(output, manualLines == null ? List.of() : manualLines);
            }
            output.flush();
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to canonicalize manual order input", exception);
        }
    }

    private void writeLines(DataOutputStream output, List<PromotionQuoteLineRequest> lines) throws IOException {
        output.writeInt(lines == null ? -1 : lines.size());
        if (lines == null) return;
        for (PromotionQuoteLineRequest line : lines) {
            if (line == null) { output.writeBoolean(false); continue; }
            output.writeBoolean(true);
            writeString(output, line.lineKey() == null ? null : line.lineKey().trim());
            writeLong(output, line.menuItemId());
            writeInteger(output, line.quantity());
            writeGroups(output, line.groups());
            writeComponentIds(output, line.omittedComponentIds());
            writeString(output, line.note());
            List<PromotionRewardConfigurationRequest> rewards = line.rewardConfigurations();
            output.writeInt(rewards == null ? -1 : rewards.size());
            if (rewards != null) for (PromotionRewardConfigurationRequest reward : rewards) {
                if (reward == null) { output.writeBoolean(false); continue; }
                output.writeBoolean(true);
                writeInteger(output, reward.rewardOrdinal());
                if (reward.menuItemId() != null) writeLong(output, reward.menuItemId());
                writeGroups(output, reward.groups());
            }
        }
    }

    private void writeComponentIds(DataOutputStream output, List<Long> ids) throws IOException {
        output.writeInt(ids == null ? -1 : ids.size());
        if (ids != null) for (Long id : ids) writeLong(output, id);
    }

    private void writeGroups(DataOutputStream output, List<MenuQuoteGroupRequest> groups) throws IOException {
        output.writeInt(groups == null ? -1 : groups.size());
        if (groups == null) return;
        for (MenuQuoteGroupRequest group : groups) {
            if (group == null) { output.writeBoolean(false); continue; }
            output.writeBoolean(true);
            writeLong(output, group.groupId());
            List<MenuQuoteSelectionRequest> selections = group.selections();
            output.writeInt(selections == null ? -1 : selections.size());
            if (selections != null) for (MenuQuoteSelectionRequest selection : selections) {
                if (selection == null) { output.writeBoolean(false); continue; }
                output.writeBoolean(true);
                writeLong(output, selection.menuItemId());
                writeInteger(output, selection.quantity());
                writeGroups(output, selection.groups());
            }
        }
    }

    private boolean hasV22Extension(List<PromotionQuoteLineRequest> lines,
                                    List<NormalizedManualPricedLine> manualLines) {
        if (manualLines != null && !manualLines.isEmpty()) return true;
        if (lines == null) return false;
        for (PromotionQuoteLineRequest line : lines) {
            if (line == null) continue;
            if (hasNestedCustomization(line.groups())) return true;
            for (PromotionRewardConfigurationRequest reward : line.rewardConfigurations()) {
                if (reward != null && (hasCustomization(reward.omittedComponentIds(), reward.note())
                        || hasNestedCustomization(reward.groups()))) return true;
            }
        }
        return false;
    }

    private boolean hasNestedCustomization(List<MenuQuoteGroupRequest> groups) {
        if (groups == null) return false;
        for (MenuQuoteGroupRequest group : groups) {
            if (group == null || group.selections() == null) continue;
            for (MenuQuoteSelectionRequest selection : group.selections()) {
                if (selection != null && (hasCustomization(selection.omittedComponentIds(), selection.note())
                        || hasNestedCustomization(selection.groups()))) return true;
            }
        }
        return false;
    }

    private boolean hasCustomization(List<Long> componentIds, String note) {
        return (componentIds != null && !componentIds.isEmpty()) || note != null;
    }

    private void writeNestedAndRewardCustomizations(DataOutputStream output,
                                                     List<PromotionQuoteLineRequest> lines) throws IOException {
        output.writeInt(lines == null ? -1 : lines.size());
        if (lines == null) return;
        for (PromotionQuoteLineRequest line : lines) {
            if (line == null) {
                output.writeBoolean(false);
                continue;
            }
            output.writeBoolean(true);
            writeNestedCustomizations(output, line.groups());
            List<PromotionRewardConfigurationRequest> rewards = line.rewardConfigurations();
            output.writeInt(rewards == null ? -1 : rewards.size());
            if (rewards == null) continue;
            for (PromotionRewardConfigurationRequest reward : rewards) {
                if (reward == null) {
                    output.writeBoolean(false);
                    continue;
                }
                output.writeBoolean(true);
                writeOptionalCustomization(output, reward.omittedComponentIds(), reward.note());
                writeNestedCustomizations(output, reward.groups());
            }
        }
    }

    private void writeNestedCustomizations(DataOutputStream output,
                                           List<MenuQuoteGroupRequest> groups) throws IOException {
        output.writeInt(groups == null ? -1 : groups.size());
        if (groups == null) return;
        for (MenuQuoteGroupRequest group : groups) {
            if (group == null) {
                output.writeBoolean(false);
                continue;
            }
            output.writeBoolean(true);
            List<MenuQuoteSelectionRequest> selections = group.selections();
            output.writeInt(selections == null ? -1 : selections.size());
            if (selections == null) continue;
            for (MenuQuoteSelectionRequest selection : selections) {
                if (selection == null) {
                    output.writeBoolean(false);
                    continue;
                }
                output.writeBoolean(true);
                writeOptionalCustomization(output, selection.omittedComponentIds(), selection.note());
                writeNestedCustomizations(output, selection.groups());
            }
        }
    }

    private void writeManualLines(DataOutputStream output, List<NormalizedManualPricedLine> lines) throws IOException {
        output.writeInt(lines.size());
        for (NormalizedManualPricedLine line : lines) {
            writeString(output, line.lineKey());
            writeString(output, line.description());
            writeInteger(output, line.quantity());
            writeString(output, line.unitAmount().toPlainString());
        }
    }

    private void writeOptionalCustomization(DataOutputStream output, List<Long> componentIds, String note) throws IOException {
        boolean present = (componentIds != null && !componentIds.isEmpty()) || note != null;
        output.writeBoolean(present);
        if (present) {
            writeComponentIds(output, componentIds);
            writeString(output, note);
        }
    }

    private void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(data.length); output.write(data);
    }
    private void writeLong(DataOutputStream output, Long value) throws IOException { output.writeLong(value == null ? Long.MIN_VALUE : value); }
    private void writeInteger(DataOutputStream output, Integer value) throws IOException { output.writeInt(value == null ? Integer.MIN_VALUE : value); }
    private String hex(byte[] bytes) { StringBuilder result = new StringBuilder(64); for (byte value : bytes) result.append(String.format("%02x", value)); return result.toString(); }
}
