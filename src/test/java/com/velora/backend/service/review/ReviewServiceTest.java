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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ProfessionalProfileRepository professionalProfileRepository;
    @Mock
    private UserRepository userRepository;

    private final ReviewMapper reviewMapper = new ReviewMapper();

    private ReviewService reviewService;

    private User customer;
    private User professional;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(reviewRepository, bookingRepository, professionalProfileRepository,
                userRepository, reviewMapper);

        customer = User.builder().id(1L).fullName("Cust").role(Role.CUSTOMER).build();
        professional = User.builder().id(2L).fullName("Pro").role(Role.PROFESSIONAL).build();
    }

    @Test
    void createReviewRejectsNonCompletedBooking() {
        Booking booking = Booking.builder()
                .id(30L).customer(customer).professional(professional)
                .status(BookingStatus.CONFIRMED).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(30L)).thenReturn(Optional.of(booking));

        CreateReviewRequest request = new CreateReviewRequest(5, "Great work");

        assertThatThrownBy(() -> reviewService.createReview(1L, 30L, request))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void createReviewRejectsNonOwningCustomer() {
        Booking booking = Booking.builder()
                .id(31L).customer(customer).professional(professional)
                .status(BookingStatus.COMPLETED).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(31L)).thenReturn(Optional.of(booking));

        CreateReviewRequest request = new CreateReviewRequest(5, "Great work");

        assertThatThrownBy(() -> reviewService.createReview(999L, 31L, request))
                .isInstanceOf(UnauthorizedActionException.class);
    }

    @Test
    void createReviewRejectsDuplicateReview() {
        Booking booking = Booking.builder()
                .id(32L).customer(customer).professional(professional)
                .status(BookingStatus.COMPLETED).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(32L)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(32L)).thenReturn(true);

        CreateReviewRequest request = new CreateReviewRequest(4, "Good");

        assertThatThrownBy(() -> reviewService.createReview(1L, 32L, request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void createReviewRecomputesProfessionalAverageRating() {
        Booking booking = Booking.builder()
                .id(33L).customer(customer).professional(professional)
                .status(BookingStatus.COMPLETED).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(33L)).thenReturn(Optional.of(booking));
        when(reviewRepository.existsByBookingId(33L)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review r = inv.getArgument(0);
            r.setId(500L);
            r.setCreatedAt(Instant.now());
            return r;
        });

        Review existingReview = Review.builder().id(400L).booking(booking).customer(customer).professional(professional).rating(4).build();
        when(reviewRepository.findByProfessionalId(2L)).thenReturn(List.of(existingReview,
                Review.builder().id(500L).booking(booking).customer(customer).professional(professional).rating(5).build()));

        ProfessionalProfile profile = ProfessionalProfile.builder().id(200L).user(professional).build();
        when(professionalProfileRepository.findByUserId(2L)).thenReturn(Optional.of(profile));

        CreateReviewRequest request = new CreateReviewRequest(5, "Excellent");

        ReviewResponse response = reviewService.createReview(1L, 33L, request);

        assertThat(response.rating()).isEqualTo(5);
        assertThat(profile.getAverageRating()).isEqualByComparingTo("4.50");
        assertThat(profile.getRatingCount()).isEqualTo(2);
    }

    @Test
    void professionalReviewsAreListedWithoutTheBookingId() {
        Pageable pageable = PageRequest.of(0, 20);
        Booking booking = Booking.builder()
                .id(34L).customer(customer).professional(professional)
                .status(BookingStatus.COMPLETED).scheduledAt(Instant.now()).build();
        Review review = Review.builder()
                .id(600L).booking(booking).customer(customer).professional(professional)
                .rating(5).comment("Excellent").createdAt(Instant.now())
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(professional));
        when(reviewRepository.findByProfessionalId(2L, pageable))
                .thenReturn(new PageImpl<>(List.of(review), pageable, 1));

        PageResponse<PublicReviewResponse> page = reviewService.getProfessionalReviews(2L, pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(600L);
            assertThat(item.rating()).isEqualTo(5);
            assertThat(item.comment()).isEqualTo("Excellent");
            assertThat(item.customerName()).isEqualTo("Cust");
        });
    }

    @Test
    void aProfessionalWithNoReviewsGetsAnEmptyPageNotA404() {
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findById(2L)).thenReturn(Optional.of(professional));
        when(reviewRepository.findByProfessionalId(2L, pageable)).thenReturn(Page.empty(pageable));

        PageResponse<PublicReviewResponse> page = reviewService.getProfessionalReviews(2L, pageable);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void listingReviewsRejectsAUserWhoIsNotAProfessional() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> reviewService.getProfessionalReviews(1L, PageRequest.of(0, 20)))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
