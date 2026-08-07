package com.sushimei.sushimei.backend.service;

import com.sushimei.sushimei.backend.checkout.ActiveCartNotFoundException;
import com.sushimei.sushimei.backend.checkout.CheckoutMoney;
import com.sushimei.sushimei.backend.checkout.EmptyCartException;
import com.sushimei.sushimei.backend.checkout.ParallelMoney;
import com.sushimei.sushimei.backend.checkout.ParallelMoneyResolver;

import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class CartService {

    private final CartRepository cartRepository;
    private final CheckoutMoney checkoutMoney;
    private final ParallelMoneyResolver parallelMoneyResolver;

    public CartService(CartRepository cartRepository,
                       CheckoutMoney checkoutMoney,
                       ParallelMoneyResolver parallelMoneyResolver) {
        this.cartRepository = cartRepository;
        this.checkoutMoney = checkoutMoney;
        this.parallelMoneyResolver = parallelMoneyResolver;
    }

    // ACTUALIZADO: Ahora busca el carrito asociado específicamente al número de WhatsApp
    private Cart getOrCreateActiveCart(String phoneNumber) {
        return cartRepository.findOpenCartByPhoneNumberForUpdate(phoneNumber)
                .orElseGet(() -> {
                    Cart activeCart = new Cart();
                    activeCart.setPhoneNumber(phoneNumber);
                    activeCart.setStatus("OPEN");
                    return cartRepository.save(activeCart);
                });
    }

    @Transactional
    public void addItem(String phoneNumber, String dishName, int quantity, Double unitPrice) {
        checkoutMoney.requirePositiveQuantity(quantity);
        ParallelMoney money = parallelMoneyResolver.forWriteFromLegacy(unitPrice);
        Cart cart = getOrCreateActiveCart(phoneNumber);

        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(money.legacyAmount());
        item.setUnitPriceAmount(money.numericAmount());

        cart.addItem(item);
        cartRepository.save(cart);

        System.out.println("🛒 [DB Postgres] Guardado para " + phoneNumber + ": " + quantity + "x " + dishName);
    }

    @Transactional(readOnly = true)
    public String getCartContents(String phoneNumber) {
        Cart cart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (cart == null || cart.getItems().isEmpty()) {
            return "El carrito est\u00e1 vac\u00edo.";
        }

        StringBuilder ticket = new StringBuilder("Detalle exacto de la orden:\n");
        List<BigDecimal> lineTotals = new ArrayList<>();

        for (CartItem item : cart.getItems()) {
            int itemQuantity = checkoutMoney.requirePositiveQuantity(item.getQuantity());
            BigDecimal price = parallelMoneyResolver.resolve(item.getUnitPriceAmount(), item.getUnitPrice());
            BigDecimal subtotal = checkoutMoney.calculateLineTotal(itemQuantity, price);
            lineTotals.add(subtotal);

            ticket.append("- ").append(itemQuantity).append("x ")
                    .append(item.getDishName()).append(" ($").append(formatTicketAmount(price)).append(" c/u) = $")
                    .append(formatTicketAmount(subtotal)).append("\n");
        }
        BigDecimal total = checkoutMoney.calculateCartTotal(lineTotals);
        ticket.append("\nTOTAL A PAGAR: $").append(formatTicketAmount(total)).append(" MXN");
        return ticket.toString();
    }
    // NUEVO: Método para obtener solo el total numérico (útil para guardar en la BD final)
    @Transactional(readOnly = true)
    public Double getCartTotal(String phoneNumber) {
        Cart cart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (cart == null || cart.getItems().isEmpty()) {
            return 0.0;
        }
        return getCartTotalForOrder(phoneNumber).legacyAmount();
    }

    /**
     * Strict deterministic total used when the legacy order flow creates an
     * OrderRecord. It never creates a cart and never returns zero for checkout.
     */
    @Transactional(readOnly = true)
    public ParallelMoney getCartTotalForOrder(String phoneNumber) {
        Cart cart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (cart == null) {
            throw new ActiveCartNotFoundException();
        }
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new EmptyCartException();
        }

        List<BigDecimal> lineTotals = new ArrayList<>();
        for (CartItem item : cart.getItems()) {
            int itemQuantity = checkoutMoney.requirePositiveQuantity(item.getQuantity());
            BigDecimal unitPrice = parallelMoneyResolver.resolve(item.getUnitPriceAmount(), item.getUnitPrice());
            lineTotals.add(checkoutMoney.calculateLineTotal(itemQuantity, unitPrice));
        }

        BigDecimal total = checkoutMoney.calculateCartTotal(lineTotals);
        return parallelMoneyResolver.forWriteFromExact(total);
    }
    @Transactional
    public String removeItem(String phoneNumber, String dishName, int quantity) {
        Cart cart = getOrCreateActiveCart(phoneNumber);

        Optional<CartItem> itemOpt = cart.getItems().stream()
                .filter(item -> item.getDishName().equalsIgnoreCase(dishName.trim()))
                .findFirst();

        if (itemOpt.isPresent()) {
            CartItem item = itemOpt.get();

            if (item.getQuantity() <= quantity) {
                cart.getItems().remove(item);
                item.setCart(null);
            } else {
                item.setQuantity(item.getQuantity() - quantity);
            }

            cartRepository.save(cart);
            System.out.println("🛒 [DB Postgres] Removido para " + phoneNumber + ": " + quantity + "x " + dishName);
            return "Platillo removido exitosamente. El estado actual del carrito es: \n" + getCartContents(phoneNumber);

        } else {
            return "Error interno: No se encontró el platillo '" + dishName + "' en el carrito. \n" + getCartContents(phoneNumber);
        }
    }

    @Transactional
    public void clearCart(String phoneNumber) {
        cartRepository.findOpenCartByPhoneNumberForUpdate(phoneNumber).ifPresent(cart -> {
            cart.setStatus("CLOSED");
            cartRepository.save(cart);
        });
    }

    @Transactional
    public void reopenCart(String phoneNumber) {
        Cart lastClosedCart = cartRepository.findFirstByPhoneNumberAndStatusOrderByIdDesc(phoneNumber, "CLOSED");

        if (lastClosedCart == null) {
            System.out.println("No closed cart was found for the customer.");
            return;
        }

        Cart currentOpenCart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (currentOpenCart != null) {
            List<ReopenLine> preparedLines = prepareReopenLines(currentOpenCart, lastClosedCart);
            for (ReopenLine line : preparedLines) {
                if (line.existingItem() != null) {
                    line.existingItem().setQuantity(line.quantity());
                    continue;
                }

                CartItem clonedItem = new CartItem();
                clonedItem.setDishName(line.dishName());
                clonedItem.setQuantity(line.quantity());
                clonedItem.setUnitPrice(line.cloneMoney().legacyAmount());
                clonedItem.setUnitPriceAmount(line.cloneMoney().numericAmount());
                lastClosedCart.addItem(clonedItem);
            }

            cartRepository.delete(currentOpenCart);
            System.out.println("Open cart items were merged into the reopened cart.");
        }

        lastClosedCart.setStatus("OPEN");
        cartRepository.save(lastClosedCart);
        System.out.println("Cart reopened successfully.");
    }

    private List<ReopenLine> prepareReopenLines(Cart currentOpenCart, Cart lastClosedCart) {
        Map<String, ReopenLineAccumulator> accumulatorsByDish = new LinkedHashMap<>();
        for (CartItem incomingItem : currentOpenCart.getItems()) {
            int quantity = checkoutMoney.requirePositiveQuantity(incomingItem.getQuantity());
            BigDecimal incomingPrice = parallelMoneyResolver.resolve(
                    incomingItem.getUnitPriceAmount(), incomingItem.getUnitPrice());
            String dishIdentity = incomingItem.getDishName().toLowerCase(Locale.ROOT);
            ReopenLineAccumulator accumulator = accumulatorsByDish.get(dishIdentity);
            if (accumulator == null) {
                CartItem existingItem = findExistingItem(lastClosedCart, incomingItem.getDishName());
                accumulator = ReopenLineAccumulator.create(existingItem, incomingItem.getDishName(), incomingPrice,
                        checkoutMoney, parallelMoneyResolver);
                accumulatorsByDish.put(dishIdentity, accumulator);
            } else if (accumulator.unitPrice().compareTo(incomingPrice) != 0) {
                throw new CartReopenException(CartReopenFailureReason.UNIT_PRICE_MISMATCH);
            }

            accumulator.addIncomingQuantity(quantity);
        }

        return accumulatorsByDish.values().stream()
                .map(accumulator -> accumulator.toReopenLine(parallelMoneyResolver))
                .toList();
    }

    private CartItem findExistingItem(Cart closedCart, String dishName) {
        return closedCart.getItems().stream()
                .filter(candidate -> candidate.getDishName().equalsIgnoreCase(dishName))
                .findFirst()
                .orElse(null);
    }

    private record ReopenLine(CartItem existingItem,
                              String dishName,
                              int quantity,
                              ParallelMoney cloneMoney) {
    }

    private static final class ReopenLineAccumulator {

        private final CartItem existingItem;
        private final String dishName;
        private final BigDecimal unitPrice;
        private int quantity;

        private ReopenLineAccumulator(CartItem existingItem, String dishName, BigDecimal unitPrice, int quantity) {
            this.existingItem = existingItem;
            this.dishName = dishName;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
        }

        static ReopenLineAccumulator create(CartItem existingItem,
                                             String dishName,
                                             BigDecimal incomingPrice,
                                             CheckoutMoney checkoutMoney,
                                             ParallelMoneyResolver parallelMoneyResolver) {
            if (existingItem == null) {
                return new ReopenLineAccumulator(null, dishName, incomingPrice, 0);
            }

            int existingQuantity = checkoutMoney.requirePositiveQuantity(existingItem.getQuantity());
            BigDecimal existingPrice = parallelMoneyResolver.resolve(
                    existingItem.getUnitPriceAmount(), existingItem.getUnitPrice());
            if (existingPrice.compareTo(incomingPrice) != 0) {
                throw new CartReopenException(CartReopenFailureReason.UNIT_PRICE_MISMATCH);
            }
            return new ReopenLineAccumulator(existingItem, existingItem.getDishName(), existingPrice, existingQuantity);
        }

        void addIncomingQuantity(int incomingQuantity) {
            quantity = Math.addExact(quantity, incomingQuantity);
        }

        BigDecimal unitPrice() {
            return unitPrice;
        }

        ReopenLine toReopenLine(ParallelMoneyResolver parallelMoneyResolver) {
            ParallelMoney cloneMoney = existingItem == null
                    ? parallelMoneyResolver.forWriteFromExact(unitPrice)
                    : null;
            return new ReopenLine(existingItem, dishName, quantity, cloneMoney);
        }
    }

    private String formatTicketAmount(BigDecimal amount) {
        BigDecimal withoutTrailingZeros = amount.stripTrailingZeros();
        if (withoutTrailingZeros.scale() <= 0) {
            return withoutTrailingZeros.toPlainString() + ".0";
        }
        return withoutTrailingZeros.toPlainString();
    }
}
