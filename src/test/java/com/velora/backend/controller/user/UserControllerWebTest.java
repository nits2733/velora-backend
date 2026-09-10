package com.velora.backend.controller.user;

import com.velora.backend.controller.common.WebLayerTest;

import com.velora.backend.dto.user.UserProfileResponse;
import com.velora.backend.entity.user.Role;
import com.velora.backend.service.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the two things every protected endpoint depends on and no service test sees:
 * that an anonymous caller is rejected in the API's own error shape, and that the
 * caller's identity reaches the service from the token rather than from the request.
 */
@WebMvcTest(UserController.class)
@WebLayerTest
class UserControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void anonymousRequestIsRejectedAsJsonNotAsAnHtmlLoginPage() throws Exception {
        mockMvc.perform(get("/api/users/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/api/users/profile"))
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(userService);
    }

    @Test
    void theServiceIsCalledWithTheIdFromTheSecurityContextNotTheRequest() throws Exception {
        when(userService.getProfile(42L)).thenReturn(new UserProfileResponse(
                42L, "user42@velora.test", "Forty Two", null, null, Role.CUSTOMER, Instant.now(), null));

        mockMvc.perform(get("/api/users/profile").with(as(42L, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.professionalProfile").doesNotExist());

        verify(userService).getProfile(42L);
    }

    @Test
    void anOversizedFieldIsRejectedBeforeTheServiceIsReached() throws Exception {
        String tooLongName = "x".repeat(151);

        mockMvc.perform(put("/api/users/profile")
                        .with(as(42L, Role.CUSTOMER))
                        .contentType("application/json")
                        .content("{\"fullName\":\"" + tooLongName + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("fullName"));

        verify(userService, org.mockito.Mockito.never()).updateProfile(any(), any());
    }
}
