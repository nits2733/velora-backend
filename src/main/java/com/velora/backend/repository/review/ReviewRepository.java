package com.velora.backend.repository.review;

import com.velora.backend.entity.review.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByBookingId(Long bookingId);

    /** Which of these bookings already have a review, in one query. */
    @Query("select r.booking.id from Review r where r.booking.id in :bookingIds")
    List<Long> findReviewedBookingIds(@Param("bookingIds") Collection<Long> bookingIds);

    /** Every review, used to recompute the aggregate - never returned to a client. */
    List<Review> findByProfessionalId(Long professionalId);

    Page<Review> findByProfessionalId(Long professionalId, Pageable pageable);
}
