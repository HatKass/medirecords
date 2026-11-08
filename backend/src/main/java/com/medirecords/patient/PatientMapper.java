package com.medirecords.patient;

import com.medirecords.patient.PatientDtos.PatientRequest;
import com.medirecords.patient.PatientDtos.PatientResponse;
import org.springframework.stereotype.Component;

/**
 * Hand-written mapping between API DTOs and the entity. With one entity and a dozen fields this is
 * clearer than a code generator, and it is where normalization is applied on the way in.
 */
@Component
public class PatientMapper {

    public PatientDetails toDetails(PatientRequest request) {
        return new PatientDetails(
                PatientNormalizer.text(request.firstName()),
                PatientNormalizer.text(request.lastName()),
                request.dateOfBirth(),
                request.gender(),
                PatientNormalizer.email(request.email()),
                PatientNormalizer.phone(request.phone()),
                PatientNormalizer.text(request.city()),
                request.bloodType());
    }

    public PatientResponse toResponse(Patient patient) {
        return new PatientResponse(
                patient.getId(),
                patient.getFirstName(),
                patient.getLastName(),
                patient.getDateOfBirth(),
                patient.getGender(),
                patient.getEmail(),
                patient.getPhone(),
                patient.getCity(),
                patient.getBloodType(),
                patient.getCreatedAt(),
                patient.getUpdatedAt(),
                patient.getVersion());
    }
}
