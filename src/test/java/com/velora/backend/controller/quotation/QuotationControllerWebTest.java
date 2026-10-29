package com.velora.backend.controller.quotation;

import com.velora.backend.controller.common.WebLayerTest;
import com.velora.backend.entity.user.Role;
import com.velora.backend.service.quotation.QuotationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuotationController.class)
@WebLayerTest
class QuotationControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuotationService quotationService;

    @Test
    void onlyACustomerMayReject() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/quotation/reject").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(quotationService);
    }

    @Test
    void rejectPassesTheOptionalReasonThrough() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/quotation/reject")
                        .with(as(7L, Role.CUSTOMER))
                        .contentType("application/json")
                        .content("{\"reason\":\"Over my budget\"}"))
                .andExpect(status().isOk());

        verify(quotationService).reject(7L, 5L, "Over my budget");
    }

    @Test
    void rejectStillWorksWithNoBodyAtAll() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/quotation/reject").with(as(7L, Role.CUSTOMER)))
                .andExpect(status().isOk());

        verify(quotationService).reject(7L, 5L, null);
    }

    @Test
    void rejectRefusesAReasonLongerThanTheColumn() throws Exception {
        mockMvc.perform(patch("/api/bookings/5/quotation/reject")
                        .with(as(7L, Role.CUSTOMER))
                        .contentType("application/json")
                        .content("{\"reason\":\"" + "x".repeat(301) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(quotationService);
    }
}
