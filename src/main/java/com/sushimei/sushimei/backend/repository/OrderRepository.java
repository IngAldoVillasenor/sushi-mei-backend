package com.sushimei.sushimei.backend.repository;

import com.sushimei.sushimei.backend.entity.OrderRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<OrderRecord, Long> {
    // Spring Data JPA crea la query automáticamente para traer la orden más reciente (Descendente)
    OrderRecord findFirstByPhoneNumberAndStatusOrderByCreatedAtDesc(String phoneNumber, String status);

    // Devuelve la lista de órdenes filtradas por estado y ordenadas por fecha de creación ascendente
    List<OrderRecord> findByStatusOrderByCreatedAtAsc(String status);

    List<OrderRecord> findByStatusInOrderByCreatedAtAsc(List<String> statuses);
}
