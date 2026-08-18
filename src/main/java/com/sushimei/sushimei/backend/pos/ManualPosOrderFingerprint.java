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

    public String fingerprint(ManualPosOrderRequest request,
                              String deliveryAddress,
                              String pickupName,
                              BigDecimal cashDenomination) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeString(output, request.fulfillmentType() == null ? null : request.fulfillmentType().name());
            writeString(output, request.paymentMethod() == null ? null : request.paymentMethod().name());
            writeString(output, deliveryAddress);
            writeString(output, pickupName);
            writeString(output, cashDenomination == null ? null : cashDenomination.toPlainString());
            writeLines(output, request.lines());
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

    private void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) { output.writeInt(-1); return; }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(data.length); output.write(data);
    }
    private void writeLong(DataOutputStream output, Long value) throws IOException { output.writeLong(value == null ? Long.MIN_VALUE : value); }
    private void writeInteger(DataOutputStream output, Integer value) throws IOException { output.writeInt(value == null ? Integer.MIN_VALUE : value); }
    private String hex(byte[] bytes) { StringBuilder result = new StringBuilder(64); for (byte value : bytes) result.append(String.format("%02x", value)); return result.toString(); }
}
