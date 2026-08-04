package com.sushimei.sushimei.backend.service;

import com.sushimei.sushimei.backend.entity.Cart;
import com.sushimei.sushimei.backend.entity.CartItem;
import com.sushimei.sushimei.backend.repository.CartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

@Service
public class CartService {

    private final CartRepository cartRepository;

    public CartService(CartRepository cartRepository) {
        this.cartRepository = cartRepository;
    }

    // ACTUALIZADO: Ahora busca el carrito asociado específicamente al número de WhatsApp
    private Cart getOrCreateActiveCart(String phoneNumber) {
        // Asegúrate de agregar este método a tu CartRepository:
        // Cart findByPhoneNumberAndStatus(String phoneNumber, String status);
        Cart activeCart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (activeCart == null) {
            activeCart = new Cart();
            activeCart.setPhoneNumber(phoneNumber);
            activeCart.setStatus("OPEN");
            activeCart = cartRepository.save(activeCart);
        }
        return activeCart;
    }

    @Transactional
    public void addItem(String phoneNumber, String dishName, int quantity, Double unitPrice) {
        Cart cart = getOrCreateActiveCart(phoneNumber);

        CartItem item = new CartItem();
        item.setDishName(dishName);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);

        cart.addItem(item);
        cartRepository.save(cart);

        System.out.println("🛒 [DB Postgres] Guardado para " + phoneNumber + ": " + quantity + "x " + dishName);
    }

    @Transactional(readOnly = true)
    public String getCartContents(String phoneNumber) {
        Cart cart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (cart == null || cart.getItems().isEmpty()) {
            return "El carrito está vacío.";
        }

        StringBuilder ticket = new StringBuilder("Detalle exacto de la orden:\n");
        double total = 0.0;

        for (CartItem item : cart.getItems()) {
            double price = (item.getUnitPrice() != null) ? item.getUnitPrice() : 0.0;
            double subtotal = item.getQuantity() * price;
            total += subtotal;

            ticket.append("- ").append(item.getQuantity()).append("x ")
                    .append(item.getDishName()).append(" ($").append(price).append(" c/u) = $")
                    .append(subtotal).append("\n");
        }
        ticket.append("\nTOTAL A PAGAR: $").append(total).append(" MXN");
        return ticket.toString();
    }

    // NUEVO: Método para obtener solo el total numérico (útil para guardar en la BD final)
    @Transactional(readOnly = true)
    public Double getCartTotal(String phoneNumber) {
        Cart cart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (cart == null || cart.getItems().isEmpty()) return 0.0;

        return cart.getItems().stream()
                .mapToDouble(item -> item.getQuantity() * (item.getUnitPrice() != null ? item.getUnitPrice() : 0.0))
                .sum();
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
        Cart cart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");
        if (cart != null) {
            cart.setStatus("CLOSED"); // Cambiamos el estado para que el próximo pedido genere un carrito nuevo
            cartRepository.save(cart);
        }
    }

    @Transactional
    public void reopenCart(String phoneNumber) {
        // 1. Buscamos el carrito que se bloqueó al generar la orden que acaba de ser rechazada
        Cart lastClosedCart = cartRepository.findFirstByPhoneNumberAndStatusOrderByIdDesc(phoneNumber, "CLOSED");

        if (lastClosedCart != null) {
            // 2. Verificamos si el cliente ya había empezado un carrito nuevo
            Cart currentOpenCart = cartRepository.findByPhoneNumberAndStatus(phoneNumber, "OPEN");

            if (currentOpenCart != null) {
                // FUSIÓN INTELIGENTE: Traspasamos los alimentos al carrito que estamos reviviendo
                for (CartItem newItem : currentOpenCart.getItems()) {

                    // Verificamos si el platillo nuevo ya existía en el carrito viejo
                    java.util.Optional<CartItem> existingItemOpt = lastClosedCart.getItems().stream()
                            .filter(oldItem -> oldItem.getDishName().equalsIgnoreCase(newItem.getDishName()))
                            .findFirst();

                    if (existingItemOpt.isPresent()) {
                        // Si ya existía, solo sumamos las cantidades (Ej. 1x Coca + 1x Coca = 2x Coca)
                        CartItem existingItem = existingItemOpt.get();
                        existingItem.setQuantity(existingItem.getQuantity() + newItem.getQuantity());
                    } else {
                        // Si es un platillo totalmente nuevo, creamos una copia para evitar errores de persistencia
                        CartItem clonedItem = new CartItem();
                        clonedItem.setDishName(newItem.getDishName());
                        clonedItem.setQuantity(newItem.getQuantity());
                        clonedItem.setUnitPrice(newItem.getUnitPrice());

                        lastClosedCart.addItem(clonedItem);
                    }
                }

                // Eliminamos el carrito temporal porque sus artículos ya fueron salvados
                cartRepository.delete(currentOpenCart);
                System.out.println("🔄 [DB Postgres] Se fusionaron los nuevos platillos con la orden rechazada.");
            }

            // 3. Le devolvemos la vida al carrito consolidado
            lastClosedCart.setStatus("OPEN");
            cartRepository.save(lastClosedCart);

            System.out.println("🔄 [DB Postgres] Carrito restaurado exitosamente para " + phoneNumber);
        } else {
            System.out.println("⚠️ No se encontró carrito cerrado para el número " + phoneNumber + ".");
        }
    }
}