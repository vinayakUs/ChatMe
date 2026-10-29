package com.example.registration.grpc;

import java.time.Duration;

import org.springframework.grpc.server.service.GrpcService;

import com.example.registration.ratelimit.RateLimitExceededException;
import com.example.registration.service.RegistrationService;
import com.example.registration.session.RegistrationSession;
import com.example.registration.session.SessionMetadata;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.grpc.stub.StreamObserver;

/**
 * RegistrationGrpcService
 */
@GrpcService
public class RegistrationGrpcService extends RegistrationServiceGrpc.RegistrationServiceImplBase {

	private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

	private final RegistrationService registrationService;

	public RegistrationGrpcService(RegistrationService registrationService) {
		this.registrationService = registrationService;
	}

	@Override
	public void createSession(
			CreateRegistrationSessionRequest request,
			StreamObserver<CreateRegistraionSessionResponse> responseObserver) {

		try {
			Phonenumber.PhoneNumber e164 = PHONE_UTIL.parse("+" + request.getE164(), null);

			RegistrationSession registrationSession = registrationService.createRegistrationSession(
					e164,
					request.getRateLimitCollationKey(),
					SessionMetadata.newBuilder()
							.setMmc(request.getMmc())
							.setMnc(request.getMnc())
							.setAccountExistWithE164(request.getAccountExistWithE164())
							.build());

			responseObserver.onNext(
					registrationService.buildRegistraionSessionResponse(registrationSession));
			responseObserver.onCompleted();

		}

		catch (RateLimitExceededException ex) {

			CreateRegistrationSessionError.Builder errorBuilder = CreateRegistrationSessionError
					.newBuilder()
					.setErrorType(
							CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_RATE_LIMITED)
					.setMayRetry(ex.getRetryAfter().isPresent());

			ex.getRetryAfter().ifPresent((Duration dur) -> {
				errorBuilder.setRetryAfterSeconds(dur.getSeconds());
			});

			responseObserver.onNext(CreateRegistraionSessionResponse.newBuilder()
					.setError(errorBuilder.build())
					.build());
			responseObserver.onCompleted();

		}

		catch (NumberParseException ex) {
			CreateRegistrationSessionError.Builder errorBuilder = CreateRegistrationSessionError
					.newBuilder()
					.setErrorType(
							CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER)
					.setMayRetry(false);

			responseObserver.onNext(CreateRegistraionSessionResponse.newBuilder()
					.setError(errorBuilder.build())
					.build());
			responseObserver.onCompleted();

		}

		catch (Exception e) {
			e.printStackTrace();
			responseObserver.onNext(CreateRegistraionSessionResponse.newBuilder()
					.setError(CreateRegistrationSessionError.newBuilder().build())
					.build());
			responseObserver.onCompleted();
		}
	}


	@Override 
	public void sendVerificationCode(
		SendVerificationCodeRequest request,
		StreamObserver<SendVerficationCodeResponse> responseObserver
	){
		
	}
}
