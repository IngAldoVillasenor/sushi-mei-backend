package com.sushimei.sushimei.backend.pos;

import static org.assertj.core.api.Assertions.assertThat;

import com.sushimei.sushimei.backend.catalog.MenuQuoteGroupRequest;
import com.sushimei.sushimei.backend.catalog.MenuQuoteSelectionRequest;
import com.sushimei.sushimei.backend.entity.OrderFulfillmentType;
import com.sushimei.sushimei.backend.entity.OrderPaymentMethod;
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
import org.junit.jupiter.api.Test;

class ManualPosOrderFingerprintTest {

    private final ManualPosOrderFingerprint fingerprint = new ManualPosOrderFingerprint();

    @Test
    void preV22RequestsKeepTheirLegacyFingerprintWhileActualV22CustomizationChangesIt() {
        MenuQuoteSelectionRequest nestedSelection = new MenuQuoteSelectionRequest(20L, 1, List.of());
        MenuQuoteGroupRequest group = new MenuQuoteGroupRequest(10L, List.of(nestedSelection));
        PromotionRewardConfigurationRequest reward = new PromotionRewardConfigurationRequest(1, 30L, List.of(group));
        List<PromotionQuoteLineRequest> legacyLines = List.of(line(group, reward));

        String legacy = LegacyFingerprint.serialize(legacyLines);
        String unchanged = fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH,
                null, "Ana", null, legacyLines, List.of());
        assertThat(unchanged).isEqualTo(legacy);

        MenuQuoteSelectionRequest omittedNested = new MenuQuoteSelectionRequest(20L, 1, List.of(), List.of(101L), null);
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(line(new MenuQuoteGroupRequest(10L, List.of(omittedNested)), reward)), List.of()))
                .isNotEqualTo(legacy);

        MenuQuoteSelectionRequest notedNested = new MenuQuoteSelectionRequest(20L, 1, List.of(), List.of(), "No sauce");
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(line(new MenuQuoteGroupRequest(10L, List.of(notedNested)), reward)), List.of()))
                .isNotEqualTo(legacy);

        PromotionRewardConfigurationRequest rewardWithOmission = new PromotionRewardConfigurationRequest(1, 30L,
                List.of(group), List.of(102L), null);
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(line(group, rewardWithOmission)), List.of())).isNotEqualTo(legacy);

        PromotionRewardConfigurationRequest rewardWithNote = new PromotionRewardConfigurationRequest(1, 30L,
                List.of(group), List.of(), "Reward note");
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(line(group, rewardWithNote)), List.of())).isNotEqualTo(legacy);

        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                legacyLines, List.of(new NormalizedManualPricedLine("manual", "Manual", 1,
                        new BigDecimal("5.00"), new BigDecimal("5.00"))))).isNotEqualTo(legacy);
    }

    @Test
    void rewardGroupCustomizationRecursivelyParticipatesInTheV22Trailer() {
        MenuQuoteSelectionRequest legacySelection = new MenuQuoteSelectionRequest(40L, 1, List.of());
        MenuQuoteGroupRequest legacyRewardGroup = new MenuQuoteGroupRequest(30L, List.of(legacySelection));
        PromotionRewardConfigurationRequest legacyReward = new PromotionRewardConfigurationRequest(1, 20L,
                List.of(legacyRewardGroup));
        List<PromotionQuoteLineRequest> legacyLines = List.of(lineWithReward(legacyReward));
        String legacy = LegacyFingerprint.serialize(legacyLines);

        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                legacyLines, List.of())).isEqualTo(legacy);

        MenuQuoteSelectionRequest omittedSelection = new MenuQuoteSelectionRequest(40L, 1, List.of(), List.of(501L), null);
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(lineWithReward(new PromotionRewardConfigurationRequest(1, 20L,
                        List.of(new MenuQuoteGroupRequest(30L, List.of(omittedSelection)))))), List.of()))
                .isNotEqualTo(legacy);

        MenuQuoteSelectionRequest notedSelection = new MenuQuoteSelectionRequest(40L, 1, List.of(), List.of(), "No sauce");
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(lineWithReward(new PromotionRewardConfigurationRequest(1, 20L,
                        List.of(new MenuQuoteGroupRequest(30L, List.of(notedSelection)))))), List.of()))
                .isNotEqualTo(legacy);

        MenuQuoteSelectionRequest deeperSelection = new MenuQuoteSelectionRequest(60L, 1, List.of(), List.of(), "Separate");
        MenuQuoteSelectionRequest parentSelection = new MenuQuoteSelectionRequest(40L, 1,
                List.of(new MenuQuoteGroupRequest(50L, List.of(deeperSelection))));
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(lineWithReward(new PromotionRewardConfigurationRequest(1, 20L,
                        List.of(new MenuQuoteGroupRequest(30L, List.of(parentSelection)))))), List.of()))
                .isNotEqualTo(legacy);

        PromotionRewardConfigurationRequest restoredReward = new PromotionRewardConfigurationRequest(1, 20L,
                List.of(new MenuQuoteGroupRequest(30L, List.of(new MenuQuoteSelectionRequest(40L, 1, List.of())))));
        assertThat(fingerprint.fingerprint(OrderFulfillmentType.PICKUP, OrderPaymentMethod.CASH, null, "Ana", null,
                List.of(lineWithReward(restoredReward)), List.of())).isEqualTo(legacy);
    }

    private PromotionQuoteLineRequest line(MenuQuoteGroupRequest group,
                                           PromotionRewardConfigurationRequest reward) {
        return new PromotionQuoteLineRequest("line", 1L, 1, List.of(group), List.of(reward), List.of(), null);
    }

    private PromotionQuoteLineRequest lineWithReward(PromotionRewardConfigurationRequest reward) {
        return new PromotionQuoteLineRequest("line", 1L, 1, List.of(), List.of(reward), List.of(), null);
    }

    /** Exact pre-V22 serializer fixture: nested/reward occurrence customization did not yet exist. */
    private static final class LegacyFingerprint {
        private static String serialize(List<PromotionQuoteLineRequest> lines) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream output = new DataOutputStream(bytes);
                writeString(output, OrderFulfillmentType.PICKUP.name());
                writeString(output, OrderPaymentMethod.CASH.name());
                writeString(output, null);
                writeString(output, "Ana");
                writeString(output, null);
                writeLines(output, lines);
                output.flush();
                return hex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            } catch (IOException | NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }

        private static void writeLines(DataOutputStream output, List<PromotionQuoteLineRequest> lines) throws IOException {
            output.writeInt(lines == null ? -1 : lines.size());
            if (lines == null) return;
            for (PromotionQuoteLineRequest line : lines) {
                if (line == null) {
                    output.writeBoolean(false);
                    continue;
                }
                output.writeBoolean(true);
                writeString(output, line.lineKey() == null ? null : line.lineKey().trim());
                writeLong(output, line.menuItemId());
                writeInteger(output, line.quantity());
                writeGroups(output, line.groups());
                writeComponentIds(output, line.omittedComponentIds());
                writeString(output, line.note());
                List<PromotionRewardConfigurationRequest> rewards = line.rewardConfigurations();
                output.writeInt(rewards == null ? -1 : rewards.size());
                if (rewards == null) continue;
                for (PromotionRewardConfigurationRequest reward : rewards) {
                    if (reward == null) {
                        output.writeBoolean(false);
                        continue;
                    }
                    output.writeBoolean(true);
                    writeInteger(output, reward.rewardOrdinal());
                    if (reward.menuItemId() != null) writeLong(output, reward.menuItemId());
                    writeGroups(output, reward.groups());
                }
            }
        }

        private static void writeGroups(DataOutputStream output, List<MenuQuoteGroupRequest> groups) throws IOException {
            output.writeInt(groups == null ? -1 : groups.size());
            if (groups == null) return;
            for (MenuQuoteGroupRequest group : groups) {
                if (group == null) {
                    output.writeBoolean(false);
                    continue;
                }
                output.writeBoolean(true);
                writeLong(output, group.groupId());
                List<MenuQuoteSelectionRequest> selections = group.selections();
                output.writeInt(selections == null ? -1 : selections.size());
                if (selections == null) continue;
                for (MenuQuoteSelectionRequest selection : selections) {
                    if (selection == null) {
                        output.writeBoolean(false);
                        continue;
                    }
                    output.writeBoolean(true);
                    writeLong(output, selection.menuItemId());
                    writeInteger(output, selection.quantity());
                    writeGroups(output, selection.groups());
                }
            }
        }

        private static void writeComponentIds(DataOutputStream output, List<Long> ids) throws IOException {
            output.writeInt(ids == null ? -1 : ids.size());
            if (ids != null) for (Long id : ids) writeLong(output, id);
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            if (value == null) {
                output.writeInt(-1);
                return;
            }
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            output.writeInt(data.length);
            output.write(data);
        }

        private static void writeLong(DataOutputStream output, Long value) throws IOException {
            output.writeLong(value == null ? Long.MIN_VALUE : value);
        }

        private static void writeInteger(DataOutputStream output, Integer value) throws IOException {
            output.writeInt(value == null ? Integer.MIN_VALUE : value);
        }

        private static String hex(byte[] bytes) {
            StringBuilder result = new StringBuilder(64);
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        }
    }
}
