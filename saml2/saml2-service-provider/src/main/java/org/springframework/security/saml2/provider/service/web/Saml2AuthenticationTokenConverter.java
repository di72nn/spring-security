/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.saml2.provider.service.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.CodecPolicy;
import org.apache.commons.codec.binary.Base64;

import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.core.Saml2ParameterNames;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.servlet.HttpSessionSaml2AuthenticationRequestRepository;
import org.springframework.security.saml2.provider.service.servlet.Saml2AuthenticationRequestRepository;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.Assert;

/**
 * An {@link AuthenticationConverter} that generates a {@link Saml2AuthenticationToken}
 * appropriate for authenticated a SAML 2.0 Assertion against an
 * {@link org.springframework.security.authentication.AuthenticationManager}.
 *
 * @author Josh Cummings
 * @since 5.4
 */
public final class Saml2AuthenticationTokenConverter implements AuthenticationConverter {

	private static Base64 BASE64 = new Base64(0, new byte[] { '\n' }, false, CodecPolicy.STRICT);

	private final Converter<HttpServletRequest, RelyingPartyRegistration> relyingPartyRegistrationResolver;

	private Function<HttpServletRequest, AbstractSaml2AuthenticationRequest> loader;

	/**
	 * Constructs a {@link Saml2AuthenticationTokenConverter} given a strategy for
	 * resolving {@link RelyingPartyRegistration}s
	 * @param relyingPartyRegistrationResolver the strategy for resolving
	 * {@link RelyingPartyRegistration}s
	 * @deprecated Use
	 * {@link Saml2AuthenticationTokenConverter#Saml2AuthenticationTokenConverter(RelyingPartyRegistrationResolver)}
	 * instead
	 */
	@Deprecated
	public Saml2AuthenticationTokenConverter(
			Converter<HttpServletRequest, RelyingPartyRegistration> relyingPartyRegistrationResolver) {
		Assert.notNull(relyingPartyRegistrationResolver, "relyingPartyRegistrationResolver cannot be null");
		this.relyingPartyRegistrationResolver = relyingPartyRegistrationResolver;
		this.loader = new HttpSessionSaml2AuthenticationRequestRepository()::loadAuthenticationRequest;
	}

	public Saml2AuthenticationTokenConverter(RelyingPartyRegistrationResolver relyingPartyRegistrationResolver) {
		this(adaptToConverter(relyingPartyRegistrationResolver));
	}

	private static Converter<HttpServletRequest, RelyingPartyRegistration> adaptToConverter(
			RelyingPartyRegistrationResolver relyingPartyRegistrationResolver) {
		Assert.notNull(relyingPartyRegistrationResolver, "relyingPartyRegistrationResolver cannot be null");
		return (request) -> relyingPartyRegistrationResolver.resolve(request, null);
	}

	@Override
	public Saml2AuthenticationToken convert(HttpServletRequest request) {
		RelyingPartyRegistration relyingPartyRegistration = this.relyingPartyRegistrationResolver.convert(request);
		if (relyingPartyRegistration == null) {
			return null;
		}
		String saml2Response = request.getParameter(Saml2ParameterNames.SAML_RESPONSE);
		if (saml2Response == null) {
			return null;
		}
		byte[] b = samlDecode(saml2Response);
		saml2Response = inflateIfRequired(request, b);
		AbstractSaml2AuthenticationRequest authenticationRequest = loadAuthenticationRequest(request);
		return new Saml2AuthenticationToken(relyingPartyRegistration, saml2Response, authenticationRequest);
	}

	/**
	 * Use the given {@link Saml2AuthenticationRequestRepository} to load authentication
	 * request.
	 * @param authenticationRequestRepository the
	 * {@link Saml2AuthenticationRequestRepository} to use
	 * @since 5.6
	 */
	public void setAuthenticationRequestRepository(
			Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> authenticationRequestRepository) {
		Assert.notNull(authenticationRequestRepository, "authenticationRequestRepository cannot be null");
		this.loader = authenticationRequestRepository::loadAuthenticationRequest;
	}

	private AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
		return this.loader.apply(request);
	}

	private String inflateIfRequired(HttpServletRequest request, byte[] b) {
		if (HttpMethod.GET.matches(request.getMethod())) {
			return samlInflate(b);
		}
		return new String(b, StandardCharsets.UTF_8);
	}

	private byte[] samlDecode(String base64EncodedPayload) {
		try {
			return BASE64.decode(base64EncodedPayload);
		}
		catch (Exception ex) {
			throw new Saml2AuthenticationException(
					new Saml2Error(Saml2ErrorCodes.INVALID_RESPONSE, "Failed to decode SAMLResponse"), ex);
		}
	}

	private String samlInflate(byte[] b) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			InflaterOutputStream inflaterOutputStream = new InflaterOutputStream(out, new Inflater(true));
			inflaterOutputStream.write(b);
			inflaterOutputStream.finish();
			return out.toString(StandardCharsets.UTF_8.name());
		}
		catch (Exception ex) {
			throw new Saml2AuthenticationException(
					new Saml2Error(Saml2ErrorCodes.INVALID_RESPONSE, "Unable to inflate string"), ex);
		}
	}

}
