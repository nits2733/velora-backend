package com.velora.backend.mapper.review;

import com.velora.backend.dto.review.PublicReviewResponse;
import com.velora.backend.dto.review.ReviewResponse;
import com.velora.backend.entity.review.Review;
import org.springframework.stereotype.Component;

@Component
public class ReviewMapper {

    /** Public listing shape - no bookingId, plus the reviewer's name. */
    public PublicReviewResponse toPublicResponse(Review review) {
        return new PublicReviewResponse(
                review.getId(),
                review.getRating(),
                review.getComment(),
                review.getCustomer().getFullName(),
                review.getCreatedAt()
        );
    }

    public ReviewResponse toResponse(Review review) {
        return new ReviewResponse(
                review.getId(),
                review.getBooking().getId(),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt()
        );
    }
}
