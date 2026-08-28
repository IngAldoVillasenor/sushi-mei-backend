package com.sushimei.sushimei.backend.agent;

import com.sushimei.sushimei.backend.catalog.MenuCatalogRepository;
import com.sushimei.sushimei.backend.catalog.MenuItem;
import com.sushimei.sushimei.backend.catalog.MenuItemPricingMode;
import com.sushimei.sushimei.backend.tools.OrderTools;
import com.sushimei.sushimei.backend.tools.ResolvedMenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves explicit cart additions from the current message before the language model is consulted. */
@Component
@ConditionalOnProperty(prefix = "sushimei.features.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DeterministicCartAddRouter {

    private static final Logger log = LoggerFactory.getLogger(DeterministicCartAddRouter.class);
    private static final String HANDLED = "Los productos expl\u00edcitos fueron procesados contra el cat\u00e1logo.";
    private static final Set<String> IGNORED_TOKENS = Set.of(
            "a", "al", "con", "de", "del", "el", "la", "las", "los", "me", "mi", "para", "por", "favor",
            "que", "quiero", "quisiera", "dame", "ponme", "pon", "agrega", "agregame", "agregar", "agregas",
            "anade", "incluye", "ordena", "ordenar", "ordenarme", "pido", "pedir", "un", "una", "unos", "unas",
            "y", "tambien", "otro", "otra", "pedido", "producto", "productos", "bebida", "japonesa", "refresco",
            "ml", "l", "ninguno", "ninguna", "sean", "sea", "entonces", "hola", "buenas", "tardes");
    private static final Map<String, Integer> QUANTITY_WORDS = Map.of(
            "uno", 1, "dos", 2, "tres", 3, "cuatro", 4, "cinco", 5,
            "seis", 6, "siete", 7, "ocho", 8, "nueve", 9, "diez", 10);

    private final MenuCatalogRepository menuCatalogRepository;
    private final OrderTools orderTools;

    public DeterministicCartAddRouter(MenuCatalogRepository menuCatalogRepository, OrderTools orderTools) {
        this.menuCatalogRepository = menuCatalogRepository;
        this.orderTools = orderTools;
    }

    public Optional<String> tryAdd(String phoneNumber, String message) {
        if (!AiToolSafetyGuard.isAddRequest(message)
                || AiToolSafetyGuard.isRemoveRequest(message)
                || AiToolSafetyGuard.isInformationRequest(message)
                || AiToolSafetyGuard.isFinishOrderIntent(message)
                || AiToolSafetyGuard.isCurrentCartQuery(AiToolSafetyGuard.normalize(message))) {
            return Optional.empty();
        }

        List<SourceToken> messageTokens = identityTokens(message);
        List<Match> candidates = menuCatalogRepository
                .findByActiveTrueAndStandaloneOrderableTrueOrderByCategoryAscDisplayOrderAscNameAscIdAsc().stream()
                .filter(MenuItem::isAvailable)
                .filter(item -> item.getPricingMode() == MenuItemPricingMode.BASE_PLUS_ADJUSTMENTS)
                .filter(item -> item.getPriceAmount().signum() > 0)
                .flatMap(item -> match(item, message, messageTokens).stream())
                .toList();
        List<Match> matches = selectUnambiguousNonOverlapping(candidates);
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        matches.stream().sorted(Comparator.comparingInt(Match::startSourceIndex)).forEach(match ->
                orderTools.addServerResolvedDishToCart(phoneNumber,
                        new ResolvedMenuItem(match.item().getName(), match.item().getPriceAmount()),
                        match.quantity()));
        log.info("AI conversation deterministic cart route outcome=HANDLED itemCount={}", matches.size());
        return Optional.of(HANDLED);
    }

    private Optional<Match> match(MenuItem item, String message, List<SourceToken> messageTokens) {
        List<SourceToken> itemIdentity = identityTokens(item.getName());
        if (itemIdentity.isEmpty()) {
            return Optional.empty();
        }
        List<String> fullIdentity = itemIdentity.stream().map(SourceToken::value).toList();
        Optional<TokenRange> range = findSequence(messageTokens, fullIdentity);
        boolean omittedCatalogNumber = false;
        if (range.isEmpty() && fullIdentity.stream().anyMatch(DeterministicCartAddRouter::isNumeric)) {
            List<String> textualIdentity = fullIdentity.stream().filter(token -> !isNumeric(token)).toList();
            if (textualIdentity.size() >= 2) {
                range = findSequence(messageTokens, textualIdentity);
                omittedCatalogNumber = range.isPresent();
            }
        }
        if (range.isEmpty()) {
            return Optional.empty();
        }

        TokenRange tokenRange = range.orElseThrow();
        if (fullIdentity.stream().noneMatch(DeterministicCartAddRouter::isNumeric)
                && hasConflictingPresentationNumber(message, tokenRange.endSourceIndex())) {
            return Optional.empty();
        }
        int quantity = requestedQuantityBefore(message, tokenRange.startSourceIndex());
        return Optional.of(new Match(item, quantity, tokenRange.startSourceIndex(), tokenRange.endSourceIndex(),
                fullIdentity.size(), omittedCatalogNumber));
    }

    private static List<Match> selectUnambiguousNonOverlapping(List<Match> candidates) {
        Map<String, List<Match>> byRange = new HashMap<>();
        for (Match candidate : candidates) {
            byRange.computeIfAbsent(candidate.startSourceIndex() + ":" + candidate.endSourceIndex(), ignored -> new ArrayList<>())
                    .add(candidate);
        }
        List<Match> unambiguous = new ArrayList<>();
        for (List<Match> sameRange : byRange.values()) {
            int bestSpecificity = sameRange.stream().mapToInt(Match::specificity).max().orElse(0);
            List<Match> best = sameRange.stream().filter(match -> match.specificity() == bestSpecificity).toList();
            if (best.size() == 1) {
                unambiguous.add(best.get(0));
            }
        }
        unambiguous.sort(Comparator.comparingInt(Match::specificity).reversed()
                .thenComparingInt(Match::startSourceIndex));
        List<Match> selected = new ArrayList<>();
        Set<Integer> occupied = new HashSet<>();
        for (Match match : unambiguous) {
            boolean overlaps = false;
            for (int index = match.startSourceIndex(); index <= match.endSourceIndex(); index++) {
                overlaps |= occupied.contains(index);
            }
            if (!overlaps) {
                selected.add(match);
                for (int index = match.startSourceIndex(); index <= match.endSourceIndex(); index++) {
                    occupied.add(index);
                }
            }
        }
        return List.copyOf(selected);
    }

    private static Optional<TokenRange> findSequence(List<SourceToken> messageTokens, List<String> identity) {
        for (int start = 0; start <= messageTokens.size() - identity.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < identity.size(); offset++) {
                if (!canonical(messageTokens.get(start + offset).value()).equals(canonical(identity.get(offset)))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return Optional.of(new TokenRange(messageTokens.get(start).sourceIndex(),
                        messageTokens.get(start + identity.size() - 1).sourceIndex()));
            }
        }
        return Optional.empty();
    }

    private static List<SourceToken> identityTokens(String value) {
        String normalized = normalizedForTokens(value);
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] tokens = normalized.split(" ");
        List<SourceToken> result = new ArrayList<>();
        for (int index = 0; index < tokens.length; index++) {
            if (!IGNORED_TOKENS.contains(tokens[index])) {
                result.add(new SourceToken(tokens[index], index));
            }
        }
        return List.copyOf(result);
    }

    private static int requestedQuantityBefore(String message, int productStart) {
        String[] tokens = normalizedForTokens(message).split(" ");
        for (int index = productStart - 1; index >= 0 && productStart - index <= 3; index--) {
            if ("y".equals(tokens[index])) {
                break;
            }
            Integer quantity = quantity(tokens[index]);
            if (quantity != null) {
                return quantity;
            }
        }
        return 1;
    }

    private static boolean hasConflictingPresentationNumber(String message, int productEnd) {
        String[] tokens = normalizedForTokens(message).split(" ");
        for (int index = productEnd + 1; index < tokens.length && index - productEnd <= 3; index++) {
            if ("y".equals(tokens[index])) {
                return false;
            }
            if (isNumeric(tokens[index])) {
                boolean precededByDe = index > 0 && "de".equals(tokens[index - 1]);
                boolean followedByUnit = index + 1 < tokens.length && Set.of("ml", "l", "litro", "litros").contains(tokens[index + 1]);
                return precededByDe || followedByUnit;
            }
        }
        return false;
    }

    private static Integer quantity(String token) {
        Integer word = QUANTITY_WORDS.get(token);
        if (word != null) {
            return word;
        }
        if (isNumeric(token)) {
            try {
                int parsed = Integer.parseInt(token);
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isNumeric(String token) {
        return !token.isBlank() && token.chars().allMatch(Character::isDigit);
    }

    private static String canonical(String token) {
        if (Set.of("roll", "rolls", "rollo", "rollos").contains(token)) {
            return "roll";
        }
        return token.length() > 3 && token.endsWith("s") ? token.substring(0, token.length() - 1) : token;
    }

    private static String normalizedForTokens(String value) {
        return AiToolSafetyGuard.normalize(value)
                .replaceAll("\\b([0-9]+)(ml|l)\\b", "$1 $2")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record SourceToken(String value, int sourceIndex) {
    }

    private record TokenRange(int startSourceIndex, int endSourceIndex) {
    }

    private record Match(MenuItem item,
                         int quantity,
                         int startSourceIndex,
                         int endSourceIndex,
                         int specificity,
                         boolean omittedCatalogNumber) {
    }
}
