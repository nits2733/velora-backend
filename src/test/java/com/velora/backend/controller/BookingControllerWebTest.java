package com.velora.backend.controller;

import com.velora.backend.entity.Role;
import com.velora.backend.service.BookingService;
import com.velora.backend.service.ProfessionalMatchingService;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.velora.backend.controller.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
