package com.velora.backend.repository.booking;

import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    Page<Booking> findByProfessionalId(Long professionalId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

    long countByProfessionalIdAndStatusIn(Long professionalId, Collection<BookingStatus> statuses);

    boolean existsByPortfolioItemId(Long portfolioItemId);
}
