package com.velora.backend.repository.review;

import com.velora.backend.entity.review.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByBookingId(Long bookingId);

    /** Every review, used to recompute the aggregate - never returned to a client. */
    List<Review> findByProfessionalId(Long professionalId);

    Page<Review> findByProfessionalId(Long professionalId, Pageable pageable);
}
