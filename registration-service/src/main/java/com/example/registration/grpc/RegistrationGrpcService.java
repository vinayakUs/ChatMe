package com.example.registration.grpc;

import org.springframework.grpc.server.service.GrpcService;

import com.example.registration.service.RegistrationService;
import com.example.registration.session.RegistrationSession;
import com.example.registration.session.SessionMetadata;
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
                    SessionMetadata.newBuilder()
                            .setMmc(request.getMmc())
                            .setMnc(request.getMnc())
                            .setAccountExistWithE164(request.getAccountExistWithE164())
                            .build());

            responseObserver.onNext(registrationService.buildRegistraionSessionResponse(registrationSession));
            responseObserver.onCompleted();

        } catch (Exception e) {
            e.printStackTrace();
            responseObserver.onNext(CreateRegistraionSessionResponse.newBuilder()
                    .setError(CreateRegistrationSessionError.newBuilder().build())
                    .build());
            responseObserver.onCompleted();
        }
    }
}
