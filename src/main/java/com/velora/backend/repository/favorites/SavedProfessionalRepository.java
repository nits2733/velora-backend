package com.velora.backend.repository.favorites;

import com.velora.backend.entity.favorites.SavedProfessional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SavedProfessionalRepository extends JpaRepository<SavedProfessional, Long> {
    Page<SavedProfessional> findByCustomerId(Long customerId, Pageable pageable);

    Optional<SavedProfessional> findByCustomerIdAndProfessionalId(Long customerId, Long professionalId);

    void deleteByCustomerIdAndProfessionalId(Long customerId, Long professionalId);
}
