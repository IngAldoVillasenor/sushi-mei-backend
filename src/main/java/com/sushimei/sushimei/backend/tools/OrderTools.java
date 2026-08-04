package com.sushimei.sushimei.backend.tools;

import com.sushimei.sushimei.backend.entity.OrderRecord;
import com.sushimei.sushimei.backend.repository.OrderRepository;
import com.sushimei.sushimei.backend.service.CartService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OrderTools {

    private final OrderRepository orderRepository;
    private final CartService cartService;

    public OrderTools(OrderRepository orderRepository, CartService cartService) {
        this.orderRepository = orderRepository;
        this.cartService = cartService;
    }

    @Tool("Agrega un platillo al carrito de compras. Úsalo SOLO cuando el cliente confirme explícitamente que quiere ordenar algo.")
    public String addDishToCart(
            @P("El número de teléfono del cliente") String phoneNumber,
            @P("El nombre exacto del platillo que el usuario quiere ordenar") String dishName,
            @P("La cantidad numérica de platillos") int quantity,
            @P("El precio unitario del platillo (extraído del menú proporcionado)") double unitPrice) {

        cartService.addItem(phoneNumber, dishName, quantity, unitPrice);
        return "Platillo agregado. El estado actual del carrito es:\n" + cartService.getCartContents(phoneNumber);
    }

    @Tool("Consulta los artículos del carrito. OBLIGATORIO usarla si el usuario pregunta por su orden o carrito.")
    public String checkCart(
            @P("El número de teléfono del cliente") String phoneNumber) {

        System.out.println("🤖 [Agente IA] Ejecutando herramienta checkCart para " + phoneNumber + "...");
        return cartService.getCartContents(phoneNumber);
    }

    @Tool("Elimina o resta un platillo del carrito de compras del usuario. Úsalo SOLO cuando el cliente pida explícitamente quitar, eliminar, cancelar o restar un producto de su orden.")
    public String removeDishFromCart(
            @P("El número de teléfono del cliente") String phoneNumber,
            @P("El nombre EXACTO del platillo que el usuario quiere quitar, tal cual como aparece en la consulta del carrito") String dishName,
            @P("La cantidad numérica de platillos que desea restar o quitar") int quantity) {

        return cartService.removeItem(phoneNumber, dishName, quantity);
    }

    @Tool("ÚSASE ÚNICAMENTE para finalizar la orden. ESTÁ ESTRICTAMENTE PROHIBIDO ejecutar esta herramienta si el cliente no te ha dado explícitamente el tipo de entrega, dirección/nombre y detalles de pago.")
    public String confirmOrder(
            @P("El teléfono del cliente. (Ej. 524771234567)")
            String phoneNumber,

            @P("OBLIGATORIO: Tipo de entrega. Solo puede ser 'DOMICILIO' o 'SUCURSAL'. NO ASUMAS ESTE DATO.")
            String deliveryType,

            @P("OBLIGATORIO: Si es domicilio, la dirección exacta proporcionada por el cliente. Si es sucursal, el nombre de quien recoge. NO INVENTES ESTE DATO.")
            String deliveryAddress,

            @P("OBLIGATORIO: Detalles del pago. Ej. 'Efectivo billete 500' o 'Transferencia (Comprobante pendiente)'. TIENES QUE PREGUNTARLO ANTES.")
            String paymentNotes) {

        if (deliveryType == null || deliveryType.trim().isEmpty()) {
            return "ERROR AL CONFIRMAR: No enviaste el tipo de entrega. Pregúntale al cliente si es para DOMICILIO o SUCURSAL y vuelve a intentarlo.";
        }

        if (deliveryAddress == null || deliveryAddress.trim().isEmpty() || deliveryAddress.length() < 5) {
            return "ERROR AL CONFIRMAR: La dirección o el nombre están vacíos o son muy cortos. PREGÚNTALE DIRECTAMENTE AL CLIENTE por su dirección exacta o nombre y espera su respuesta.";
        }

        if (paymentNotes == null || paymentNotes.trim().isEmpty() || paymentNotes.length() < 5) {
            return "ERROR: Faltan los detalles de pago. Pregúntale al cliente.";
        }

        String paymentLower = paymentNotes.toLowerCase();

        // EL NUEVO CANDADO PARA TRANSFERENCIAS
        if (paymentLower.contains("transferencia")) {
            // Si dice transferencia, pero la nota no confirma que recibió la imagen, la bloqueamos
            if (!paymentLower.contains("recibid") && !paymentLower.contains("imagen") && !paymentLower.contains("foto")) {
                return "ERROR AL CONFIRMAR: El cliente dijo transferencia pero NO HAS RECIBIDO LA FOTO DEL COMPROBANTE. Dile al cliente que estás esperando la imagen para poder continuar.";
            }
        }

        // Si es efectivo, exigimos que venga un número (el billete)
        if (paymentLower.contains("efectivo")) {
            if (!paymentLower.matches(".*\\d.*")) {
                return "ERROR AL CONFIRMAR: El cliente paga en efectivo pero NO PREGUNTASTE el billete. Pregúntale con qué billete va a pagar.";
            }
        }

        Double cartTotal = cartService.getCartTotal(phoneNumber);

        if (cartTotal == 0.0) {
            return "Error: El carrito está vacío. Pide al cliente que agregue platillos antes de confirmar.";
        }

        String cartDetails = cartService.getCartContents(phoneNumber);

        // 1. Crear el registro oficial
        OrderRecord newOrder = new OrderRecord();
        newOrder.setPhoneNumber(phoneNumber);
        newOrder.setDeliveryType(deliveryType); // Guardamos el tipo de logística
        newOrder.setDeliveryAddress(deliveryType.equalsIgnoreCase("SUCURSAL") ? "Recoge en Sucursal" : deliveryAddress);
        newOrder.setPaymentNotes(paymentNotes);
        newOrder.setOrderDetails(cartDetails);
        newOrder.setTotalAmount(cartTotal);
        newOrder.setStatus("PENDING");
        newOrder.setCreatedAt(LocalDateTime.now());

        OrderRecord savedOrder = orderRepository.save(newOrder);

        // 2. Cerrar el carrito actual del cliente
        cartService.clearCart(phoneNumber);

        System.out.println("✅ ORDEN CREADA EXITOSAMENTE: Ticket #" + savedOrder.getId() + " | Tipo: " + deliveryType.toUpperCase());

        // 3. Respuesta dinámica dependiendo del tipo de entrega
        if (deliveryType.equalsIgnoreCase("SUCURSAL")) {
            return "La orden fue guardada. El ticket es #" + savedOrder.getId() +
                    ". Dile al cliente que su pedido estará listo en aproximadamente 25 minutos y lo esperamos en la sucursal para su pago y entrega.";
        } else {
            return "La orden fue guardada. El ticket es #" + savedOrder.getId() +
                    ". Dile al cliente que su pedido ya está en preparación y saldrá a su domicilio en aproximadamente 35 a 45 minutos.";
        }
    }
}