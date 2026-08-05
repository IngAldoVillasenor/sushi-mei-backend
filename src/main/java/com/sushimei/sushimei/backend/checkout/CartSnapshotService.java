package com.sushimei.sushimei.backend.checkout;

import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class CartSnapshotService {

    private static final String OPEN_CART_STATUS = "OPEN";

    private final CartRepository cartRepository;
    private final CheckoutMoney checkoutMoney;

    public CartSnapshotService(CartRepository cartRepository, CheckoutMoney checkoutMoney) {
        this.cartRepository = Objects.requireNonNull(cartRepository, "cartRepository must not be null");
        this.checkoutMoney = Objects.requireNonNull(checkoutMoney, "checkoutMoney must not be null");
    }

    @Transactional(readOnly = true)
    public CartSnapshot readActiveCart(String phoneNumber) {
        String normalizedPhoneNumber = requirePhoneNumber(phoneNumber);
        List<Cart> activeCarts = cartRepository.findAllByPhoneNumberAndStatusOrderByIdAsc(
                normalizedPhoneNumber, OPEN_CART_STATUS);

        if (activeCarts.isEmpty()) {
            throw new ActiveCartNotFoundException();
        }
        if (activeCarts.size() > 1) {
            throw new MultipleActiveCartsException();
        }

        Cart cart = activeCarts.get(0);
        if (cart.getId() == null) {
            throw new InvalidCartItemException(InvalidCartItemReason.MISSING_CART_ID);
        }
        if (cart.getItems() == null || cart.getItems().isEmpty()) {
            throw new EmptyCartException();
        }

        List<CartLineSnapshot> items = cart.getItems().stream()
                .map(this::toLineSnapshot)
                .sorted(Comparator.comparing(CartLineSnapshot::cartItemId))
                .toList();
        BigDecimal total;
        try {
            total = checkoutMoney.calculateCartTotal(items.stream()
                    .map(CartLineSnapshot::lineTotal)
                    .toList());
        } catch (ArithmeticException exception) {
            throw new InvalidCartItemException(InvalidCartItemReason.CART_TOTAL_OVERFLOW);
        }

        return new CartSnapshot(cart.getId(), items, total);
    }

    CartLineSnapshot toLineSnapshot(CartItem item) {
        if (item == null || item.getId() == null) {
            throw new InvalidCartItemException(InvalidCartItemReason.MISSING_ITEM_ID);
        }

        String dishName = validateDishName(item.getDishName());
        int quantity;
        try {
            quantity = checkoutMoney.requirePositiveQuantity(item.getQuantity());
        } catch (IllegalArgumentException exception) {
            throw new InvalidCartItemException(InvalidCartItemReason.INVALID_QUANTITY);
        }

        BigDecimal unitPrice;
        try {
            unitPrice = checkoutMoney.normalizeLegacyUnitPrice(item.getUnitPrice());
        } catch (IllegalArgumentException exception) {
            throw new InvalidCartItemException(InvalidCartItemReason.INVALID_UNIT_PRICE);
        }

        BigDecimal lineTotal;
        try {
            lineTotal = checkoutMoney.calculateLineTotal(quantity, unitPrice);
        } catch (ArithmeticException exception) {
            throw new InvalidCartItemException(InvalidCartItemReason.LINE_TOTAL_OVERFLOW);
        }

        return new CartLineSnapshot(item.getId(), dishName, quantity, unitPrice, lineTotal);
    }

    private String requirePhoneNumber(String phoneNumber) {
        if (phoneNumber == null) {
            throw new IllegalArgumentException("Phone number must not be blank.");
        }

        String trimmed = phoneNumber.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Phone number must not be blank.");
        }
        return trimmed;
    }

    private String validateDishName(String dishName) {
        if (dishName == null) {
            throw new InvalidCartItemException(InvalidCartItemReason.INVALID_DISH_NAME);
        }

        String trimmed = dishName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255) {
            throw new InvalidCartItemException(InvalidCartItemReason.INVALID_DISH_NAME);
        }
        return trimmed;
    }
}
