package com.velora.backend.controller.common;

import com.velora.backend.config.SecurityConfig;
import com.velora.backend.security.RestSecurityErrorHandler;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pairs with {@code @WebMvcTest} to give a controller slice the real security chain -
 * {@link SecurityConfig}'s rules, the real JWT filter and the real
 * {@link RestSecurityErrorHandler} - instead of the slice's permissive default.
 * See {@link WebLayerSupport} for what gets wired in and why.
 */
@Import({SecurityConfig.class, RestSecurityErrorHandler.class, WebLayerSupport.class})
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface WebLayerTest {
}
