package com.sushimei.sushimei.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "cart_items")
@Data
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String dishName;
    private Integer quantity;

    private Double unitPrice;

    // Relación: Muchos artículos pertenecen a un solo carrito
    @ManyToOne
    @JoinColumn(name = "cart_id")
    private Cart cart;
}