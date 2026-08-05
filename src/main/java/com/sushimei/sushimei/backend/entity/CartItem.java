package com.sushimei.sushimei.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

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

    @JsonIgnore
    @Column(name = "unit_price_amount", precision = 19, scale = 2)
    private BigDecimal unitPriceAmount;

    // Relación: Muchos artículos pertenecen a un solo carrito
    @ManyToOne
    @JoinColumn(name = "cart_id")
    private Cart cart;
}
