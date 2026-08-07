package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    Cart findByPhoneNumberAndStatus(String phoneNumber, String status);

    Cart findFirstByPhoneNumberAndStatusOrderByIdDesc(String phoneNumber, String status);

    List<Cart> findAllByPhoneNumberAndStatusOrderByIdAsc(String phoneNumber, String status);

    /**
     * Locks an already-existing OPEN cart before a legacy cart mutation. An
     * absent row intentionally remains unlocked and follows legacy creation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cart from Cart cart where cart.phoneNumber = :phoneNumber and cart.status = 'OPEN'")
    Optional<Cart> findOpenCartByPhoneNumberForUpdate(@Param("phoneNumber") String phoneNumber);

    /**
     * Locks one known cart identity for deterministic checkout completion. The
     * caller must still validate its owner and current status.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cart from Cart cart where cart.id = :cartId")
    Optional<Cart> findByIdForUpdate(@Param("cartId") Long cartId);
}
