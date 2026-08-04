package com.sushimei.sushimei.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "orders") // "order" es palabra reservada en SQL, mejor usar "orders"
public class OrderRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phoneNumber;

    // NUEVO: Para saber si es Domicilio o Sucursal
    private String deliveryType;

    @Column(length = 500)
    private String deliveryAddress;

    String transferReceiptPath;

    private String paymentNotes; // Ej. "Paga con billete de 500"

    @Column(columnDefinition = "TEXT")
    private String orderDetails; // Aquí podemos guardar un resumen en texto del carrito

    private Double totalAmount;

    private String status; // Ej. "PENDING", "DELIVERED"

    private LocalDateTime createdAt;
}