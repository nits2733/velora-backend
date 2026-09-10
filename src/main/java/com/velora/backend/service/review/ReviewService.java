package com.velora.backend.service.review;

import com.velora.backend.dto.review.CreateReviewRequest;
import com.velora.backend.dto.review.PublicReviewResponse;
import com.velora.backend.dto.review.ReviewResponse;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.review.Review;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.mapper.review.ReviewMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.professional.ProfessionalProfileRepository;
import com.velora.backend.repository.review.ReviewRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final ProfessionalProfileRepository professionalProfileRepository;
    private final UserRepository userRepository;
    private final ReviewMapper reviewMapper;

    /**
     * Public listing of one professional's reviews, newest first. Same 404-for-either-
     * reason rule as {@code ProfessionalService.getPublicProfile}: an unknown id and a
     * real id belonging to a customer both mean "no such public professional", and the
     * caller doesn't need to be told which. A professional with no reviews yet is a
     * different thing entirely - that's an empty page, not a 404.
     */
    @Transactional(readOnly = true)
    public PageResponse<PublicReviewResponse> getProfessionalReviews(Long professionalId, Pageable pageable) {
        User professional = userRepository.findById(professionalId)
                .filter(user -> user.getRole() == Role.PROFESSIONAL)
                .orElseThrow(() -> new ResourceNotFoundException("Professional not found: " + professionalId));

        Page<PublicReviewResponse> page = reviewRepository
                .findByProfessionalId(professional.getId(), pageable)
                .map(reviewMapper::toPublicResponse);

        return PageResponse.from(page);
    }

    @Transactional
    public ReviewResponse createReview(Long customerId, Long bookingId, CreateReviewRequest request) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (!booking.getCustomer().getId().equals(customerId)) {
            throw new UnauthorizedActionException("Only the customer on this booking can leave a review");
        }
        if (booking.getStatus() != BookingStatus.COMPLETED) {
            throw new InvalidStateTransitionException("Can only review a completed booking");
        }
        if (reviewRepository.existsByBookingId(bookingId)) {
            throw new DuplicateResourceException("This booking has already been reviewed");
        }

        Review review = Review.builder()
                .booking(booking)
                .customer(booking.getCustomer())
                .professional(booking.getProfessional())
                .rating(request.rating())
                .comment(request.comment())
                .build();

        review = reviewRepository.save(review);

        recomputeProfessionalRating(booking.getProfessional().getId());

        return reviewMapper.toResponse(review);
    }

    private void recomputeProfessionalRating(Long professionalId) {
        List<Review> reviews = reviewRepository.findByProfessionalId(professionalId);

        ProfessionalProfile profile = professionalProfileRepository.findByUserId(professionalId)
                .orElseThrow(() -> new ResourceNotFoundException("Professional profile not found: " + professionalId));

        BigDecimal total = reviews.stream()
                .map(r -> BigDecimal.valueOf(r.getRating()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal average = total.divide(new BigDecimal(reviews.size()), 2, RoundingMode.HALF_UP);

        profile.setAverageRating(average);
        profile.setRatingCount(reviews.size());
        professionalProfileRepository.save(profile);
    }
}
