package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    // Spring Boot escribirá el SQL internamente para buscar el carrito activo
    Cart findByPhoneNumberAndStatus(String phoneNumber, String status);

    Cart findFirstByPhoneNumberAndStatusOrderByIdDesc(String phoneNumber, String status);
}
