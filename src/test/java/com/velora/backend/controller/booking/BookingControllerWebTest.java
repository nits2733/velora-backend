package com.velora.backend.controller.booking;

import com.velora.backend.controller.common.WebLayerTest;

import com.velora.backend.dto.booking.BookingCountsResponse;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.user.Role;
import com.velora.backend.service.booking.BookingService;
import com.velora.backend.service.professional.ProfessionalMatchingService;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bookings carry the strictest role rules in the API - customer-only, professional-only
 * and admin-only actions on the same resource - so this is where those guards get
 * exercised as HTTP rather than as service calls.
 */
@WebMvcTest(BookingController.class)
@WebLayerTest
class BookingControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;

    @MockBean
    private ProfessionalMatchingService professionalMatchingService;

    @Test
    void theAdminQueueIsClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/bookings/awaiting-assignment"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(bookingService);
    }

    @Test
    void theAdminQueueIsClosedToCustomersAndProfessionals() throws Exception {
        mockMvc.perform(get("/api/bookings/awaiting-assignment").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/bookings/awaiting-assignment").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bookingService);
    }

    @Test
    void theAdminQueueIsOrderedOldestFirst() throws Exception {
        when(bookingService.getBookingsAwaitingAssignment(any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/bookings/awaiting-assignment").with(as(3L, Role.ADMIN)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(bookingService).getBookingsAwaitingAssignment(captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "createdAt"));
    }

    @Test
    void awaitingAssignmentIsRoutedAsALiteralPathNotAsABookingId() throws Exception {
        when(bookingService.getBookingsAwaitingAssignment(any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/bookings/awaiting-assignment").with(as(3L, Role.ADMIN)))
                .andExpect(status().isOk());

        // If /{id} had won the match, this would have been a failed Long conversion.
        verify(bookingService).getBookingsAwaitingAssignment(any(Pageable.class));
        verify(bookingService, org.mockito.Mockito.never()).getById(any(), any(), any());
    }

    @Test
    void onlyACustomerMayCancel() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/cancel").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(bookingService);
    }

    @Test
    void onlyAProfessionalMayAdvanceTheStatus() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/status")
                        .with(as(1L, Role.CUSTOMER))
                        .contentType("application/json")
                        .content("{\"status\":\"CONFIRMED\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bookingService);
    }

    @Test
    void onlyAnAdminMaySeeRecommendations() throws Exception {
        mockMvc.perform(get("/api/bookings/5/recommendations").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(professionalMatchingService);
    }

    @Test
    void theBookingListIsClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/bookings"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(bookingService);
    }

    @Test
    void repeatedStatusParamsAndActionNeededAreBoundAndPassedThrough() throws Exception {
        when(bookingService.getBookingsForUser(any(), any(), any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/bookings")
                        .with(as(7L, Role.CUSTOMER))
                        .param("status", "PENDING", "CONFIRMED")
                        .param("actionNeeded", "true"))
                .andExpect(status().isOk());

        verify(bookingService).getBookingsForUser(eq(7L), eq(Role.CUSTOMER),
                eq(List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED)), eq(true), any(Pageable.class));
    }

    @Test
    void withoutFiltersTheListIsUnfilteredAndActionNeededIsOff() throws Exception {
        when(bookingService.getBookingsForUser(any(), any(), any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/bookings").with(as(7L, Role.CUSTOMER)))
                .andExpect(status().isOk());

        verify(bookingService).getBookingsForUser(eq(7L), eq(Role.CUSTOMER), isNull(), eq(false), any(Pageable.class));
    }

    @Test
    void anUnknownStatusIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/bookings").with(as(7L, Role.CUSTOMER)).param("status", "NOPE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingService);
    }

    @Test
    void customersGetTheirNewestRequestsFirstAndProfessionalsKeepTheirScheduleOrder() throws Exception {
        when(bookingService.getBookingsForUser(any(), any(), any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/api/bookings").with(as(7L, Role.CUSTOMER))).andExpect(status().isOk());
        mockMvc.perform(get("/api/bookings").with(as(8L, Role.PROFESSIONAL))).andExpect(status().isOk());

        verify(bookingService, org.mockito.Mockito.times(2))
                .getBookingsForUser(any(), any(), any(), anyBoolean(), captor.capture());
        assertThat(captor.getAllValues().get(0).getSort())
                .isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        assertThat(captor.getAllValues().get(1).getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "scheduledAt"));
    }

    @Test
    void countsAreRoutedAsALiteralPathAndScopedToTheCaller() throws Exception {
        when(bookingService.getCounts(7L, Role.CUSTOMER)).thenReturn(new BookingCountsResponse(
                new EnumMap<>(Map.of(BookingStatus.PENDING, 2L)), 1L));

        mockMvc.perform(get("/api/bookings/counts").with(as(7L, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.PENDING").value(2))
                .andExpect(jsonPath("$.awaitingQuotationResponse").value(1));

        verify(bookingService).getCounts(7L, Role.CUSTOMER);
        verify(bookingService, org.mockito.Mockito.never()).getById(any(), any(), any());
    }

    @Test
    void countsAreClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/bookings/counts"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(bookingService);
    }

    @Test
    void cancelPassesTheOptionalReasonThrough() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/cancel")
                        .with(as(7L, Role.CUSTOMER))
                        .contentType("application/json")
                        .content("{\"reason\":\"Changed my plans\"}"))
                .andExpect(status().isOk());

        verify(bookingService).cancel(7L, 5L, "Changed my plans");
    }

    @Test
    void cancelStillWorksWithNoBodyAtAll() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/cancel").with(as(7L, Role.CUSTOMER)))
                .andExpect(status().isOk());

        verify(bookingService).cancel(7L, 5L, null);
    }

    @Test
    void cancelRejectsAReasonLongerThanTheColumn() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/cancel")
                        .with(as(7L, Role.CUSTOMER))
                        .contentType("application/json")
                        .content("{\"reason\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(bookingService);
    }
}
