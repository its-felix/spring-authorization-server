/*
 * Copyright 2020-2022 the original author or authors.
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
package org.springframework.security.oauth2.server.authorization.authentication;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.Before;
import org.junit.Test;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.jose.TestJwks;
import org.springframework.security.oauth2.jose.TestKeys;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.TestOAuth2Authorizations;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;
import org.springframework.security.oauth2.server.authorization.config.ClientSettings;
import org.springframework.security.oauth2.server.authorization.config.ProviderSettings;
import org.springframework.security.oauth2.server.authorization.context.ProviderContext;
import org.springframework.security.oauth2.server.authorization.context.ProviderContextHolder;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JwtClientAssertionAuthenticationProvider}.
 *
 * @author Rafal Lewczuk
 * @author Joe Grandja
 */
public class JwtClientAssertionAuthenticationProviderTests {
	// See RFC 7636: Appendix B.  Example for the S256 code_challenge_method
	// https://tools.ietf.org/html/rfc7636#appendix-B
	private static final String S256_CODE_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
	private static final String S256_CODE_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

	private static final String AUTHORIZATION_CODE = "code";
	private static final OAuth2TokenType AUTHORIZATION_CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);

	private static final ClientAuthenticationMethod JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD =
			new ClientAuthenticationMethod("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");

	private RegisteredClientRepository registeredClientRepository;
	private OAuth2AuthorizationService authorizationService;
	private JwtClientAssertionAuthenticationProvider authenticationProvider;
	private ProviderSettings providerSettings;

	@Before
	public void setUp() {
		this.registeredClientRepository = mock(RegisteredClientRepository.class);
		this.authorizationService = mock(OAuth2AuthorizationService.class);
		this.authenticationProvider = new JwtClientAssertionAuthenticationProvider(
				this.registeredClientRepository, this.authorizationService);
		this.providerSettings = ProviderSettings.builder().issuer("https://auth-server.com").build();
		ProviderContextHolder.setProviderContext(new ProviderContext(this.providerSettings, null));
	}

	@Test
	public void constructorWhenRegisteredClientRepositoryNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new JwtClientAssertionAuthenticationProvider(null, this.authorizationService))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("registeredClientRepository cannot be null");
	}

	@Test
	public void constructorWhenAuthorizationServiceNullThenThrowIllegalArgumentException() {
		assertThatThrownBy(() -> new JwtClientAssertionAuthenticationProvider(this.registeredClientRepository, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("authorizationService cannot be null");
	}

	@Test
	public void supportsWhenTypeOAuth2ClientAuthenticationTokenThenReturnTrue() {
		assertThat(this.authenticationProvider.supports(OAuth2ClientAuthenticationToken.class)).isTrue();
	}

	@Test
	public void authenticateWhenInvalidClientIdThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId() + "-invalid", JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, "jwt-assertion", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains(OAuth2ParameterNames.CLIENT_ID);
				});
	}

	@Test
	public void authenticateWhenUnsupportedClientAuthenticationMethodThenThrowOAuth2AuthenticationException() {
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, "jwt-assertion", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains("authentication_method");
				});
	}

	@Test
	public void authenticateWhenCredentialsNotProvidedThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, null, null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains("credentials");
				});
	}

	@Test
	public void authenticateWhenMissingJwkSetUrlThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
				.clientSettings(
						ClientSettings.builder()
								.tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256)
								.build()
				)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, "jwt-assertion", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).isEqualTo("Failed to find a Signature Verifier for Client: '" +
							registeredClient.getId() + "'. Check to ensure you have configured the JWK Set URL.");
				});
	}

	@Test
	public void authenticateWhenMissingClientSecretThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientSecret(null)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.clientSettings(
						ClientSettings.builder()
								.tokenEndpointAuthenticationSigningAlgorithm(MacAlgorithm.HS256)
								.build()
				)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, "jwt-assertion", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).isEqualTo("Failed to find a Signature Verifier for Client: '" +
							registeredClient.getId() + "'. Check to ensure you have configured the client secret.");
				});
	}

	@Test
	public void authenticateWhenMissingSigningAlgorithmThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientSecret(TestKeys.DEFAULT_ENCODED_SECRET_KEY)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, "jwt-assertion", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).isEqualTo("Failed to find a Signature Verifier for Client: '" +
							registeredClient.getId() + "'. Check to ensure you have configured a valid JWS Algorithm: 'null'.");
				});
	}

	@Test
	public void authenticateWhenInvalidCredentialsThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientSecret(TestKeys.DEFAULT_ENCODED_SECRET_KEY)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.clientSettings(
						ClientSettings.builder()
								.tokenEndpointAuthenticationSigningAlgorithm(MacAlgorithm.HS256)
								.build()
				)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, "invalid-jwt-assertion", null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.hasCauseInstanceOf(BadJwtException.class)
				.extracting(ex -> ((OAuth2AuthenticationException) ex).getError())
				.satisfies(error -> {
					assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(error.getDescription()).contains(OAuth2ParameterNames.CLIENT_ASSERTION);
				});
	}

	@Test
	public void authenticateWhenInvalidClaimsThenThrowOAuth2AuthenticationException() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientSecret(TestKeys.DEFAULT_ENCODED_SECRET_KEY)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.clientSettings(
						ClientSettings.builder()
								.tokenEndpointAuthenticationSigningAlgorithm(MacAlgorithm.HS256)
								.build()
				)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		// @formatter:off
		JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256)
				.build();
		JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
				.issuer("invalid-iss")
				.subject("invalid-sub")
				.audience(Collections.singletonList("invalid-aud"))
				.build();
		// @formatter:on

		JwtEncoder jwsEncoder = createEncoder(TestKeys.DEFAULT_ENCODED_SECRET_KEY, "HmacSHA256");
		Jwt jwtAssertion = jwsEncoder.encode(JwtEncoderParameters.from(jwsHeader, jwtClaimsSet));

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, jwtAssertion.getTokenValue(), null);
		assertThatThrownBy(() -> this.authenticationProvider.authenticate(authentication))
				.isInstanceOf(OAuth2AuthenticationException.class)
				.hasCauseInstanceOf(JwtValidationException.class)
				.extracting(ex -> (OAuth2AuthenticationException) ex)
				.satisfies(ex -> {
					assertThat(ex.getError().getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_CLIENT);
					assertThat(ex.getError().getDescription()).contains(OAuth2ParameterNames.CLIENT_ASSERTION);
					JwtValidationException jwtValidationException = (JwtValidationException) ex.getCause();
					assertThat(jwtValidationException.getErrors()).hasSize(4);		// iss, sub, aud, exp
				});
	}

	@Test
	public void authenticateWhenValidCredentialsThenAuthenticated() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientSecret(TestKeys.DEFAULT_ENCODED_SECRET_KEY)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.clientSettings(
						ClientSettings.builder()
								.tokenEndpointAuthenticationSigningAlgorithm(MacAlgorithm.HS256)
								.build()
				)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		// @formatter:off
		JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256)
				.build();
		JwtClaimsSet jwtClaimsSet = jwtClientAssertionClaims(registeredClient)
				.build();
		// @formatter:on

		JwtEncoder jwsEncoder = createEncoder(TestKeys.DEFAULT_ENCODED_SECRET_KEY, "HmacSHA256");
		Jwt jwtAssertion = jwsEncoder.encode(JwtEncoderParameters.from(jwsHeader, jwtClaimsSet));

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, jwtAssertion.getTokenValue(), null);
		OAuth2ClientAuthenticationToken authenticationResult =
				(OAuth2ClientAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getPrincipal().toString()).isEqualTo(registeredClient.getClientId());
		assertThat(authenticationResult.getCredentials()).isInstanceOf(Jwt.class);
		assertThat(authenticationResult.getRegisteredClient()).isEqualTo(registeredClient);
		assertThat(authenticationResult.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_JWT);
	}

	@Test
	public void authenticateWhenPkceAndValidCodeVerifierThenAuthenticated() {
		// @formatter:off
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.clientSecret(TestKeys.DEFAULT_ENCODED_SECRET_KEY)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_JWT)
				.clientSettings(
						ClientSettings.builder()
								.tokenEndpointAuthenticationSigningAlgorithm(MacAlgorithm.HS256)
								.build()
				)
				.build();
		// @formatter:on
		when(this.registeredClientRepository.findByClientId(eq(registeredClient.getClientId())))
				.thenReturn(registeredClient);

		OAuth2Authorization authorization = TestOAuth2Authorizations
				.authorization(registeredClient, createPkceAuthorizationParametersS256())
				.build();
		when(this.authorizationService.findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE)))
				.thenReturn(authorization);

		Map<String, Object> parameters = createPkceTokenParameters(S256_CODE_VERIFIER);

		// @formatter:off
		JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256)
				.build();
		JwtClaimsSet jwtClaimsSet = jwtClientAssertionClaims(registeredClient)
				.build();
		// @formatter:on

		JwtEncoder jwsEncoder = createEncoder(TestKeys.DEFAULT_ENCODED_SECRET_KEY, "HmacSHA256");
		Jwt jwtAssertion = jwsEncoder.encode(JwtEncoderParameters.from(jwsHeader, jwtClaimsSet));

		OAuth2ClientAuthenticationToken authentication = new OAuth2ClientAuthenticationToken(
				registeredClient.getClientId(), JWT_CLIENT_ASSERTION_AUTHENTICATION_METHOD, jwtAssertion.getTokenValue(), parameters);
		OAuth2ClientAuthenticationToken authenticationResult =
				(OAuth2ClientAuthenticationToken) this.authenticationProvider.authenticate(authentication);

		verify(this.authorizationService).findByToken(eq(AUTHORIZATION_CODE), eq(AUTHORIZATION_CODE_TOKEN_TYPE));
		assertThat(authenticationResult.isAuthenticated()).isTrue();
		assertThat(authenticationResult.getPrincipal().toString()).isEqualTo(registeredClient.getClientId());
		assertThat(authenticationResult.getCredentials()).isInstanceOf(Jwt.class);
		assertThat(authenticationResult.getRegisteredClient()).isEqualTo(registeredClient);
		assertThat(authenticationResult.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_JWT);
	}

	private JwtClaimsSet.Builder jwtClientAssertionClaims(RegisteredClient registeredClient) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(1, ChronoUnit.HOURS);
		return JwtClaimsSet.builder()
				.issuer(registeredClient.getClientId())
				.subject(registeredClient.getClientId())
				.audience(Collections.singletonList(asUrl(this.providerSettings.getIssuer(), this.providerSettings.getTokenEndpoint())))
				.issuedAt(issuedAt)
				.expiresAt(expiresAt);
	}

	private static JwtEncoder createEncoder(String secret, String algorithm) {
		SecretKey secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm);
		OctetSequenceKey secretKeyJwk = TestJwks.jwk(secretKey).build();
		JWKSource<SecurityContext> jwkSource = (jwkSelector, securityContext) ->
				jwkSelector.select(new JWKSet(secretKeyJwk));
		return new NimbusJwtEncoder(jwkSource);
	}

	private static String asUrl(String uri, String path) {
		return UriComponentsBuilder.fromUriString(uri).path(path).build().toUriString();
	}

	private static Map<String, Object> createAuthorizationCodeTokenParameters() {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.AUTHORIZATION_CODE.getValue());
		parameters.put(OAuth2ParameterNames.CODE, AUTHORIZATION_CODE);
		return parameters;
	}

	private static Map<String, Object> createPkceTokenParameters(String codeVerifier) {
		Map<String, Object> parameters = createAuthorizationCodeTokenParameters();
		parameters.put(PkceParameterNames.CODE_VERIFIER, codeVerifier);
		return parameters;
	}

	private static Map<String, Object> createPkceAuthorizationParametersS256() {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
		parameters.put(PkceParameterNames.CODE_CHALLENGE, S256_CODE_CHALLENGE);
		return parameters;
	}

}
