package com.velora.backend.repository.booking;

import com.velora.backend.entity.booking.BookingInspirationImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingInspirationImageRepository extends JpaRepository<BookingInspirationImage, Long> {
    List<BookingInspirationImage> findByBookingIdOrderByCreatedAtAsc(Long bookingId);
}
