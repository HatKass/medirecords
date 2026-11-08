package com.medirecords.patient;

import com.medirecords.audit.AuditService;
import com.medirecords.common.web.ConflictException;
import com.medirecords.common.web.ResourceNotFoundException;
import com.medirecords.patient.PatientDtos.PatientFilter;
import com.medirecords.patient.PatientDtos.PatientRequest;
import com.medirecords.patient.PatientDtos.PatientResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PatientService {

    static final String AUDIT_ENTITY = "Patient";

    private final PatientRepository repository;
    private final PatientMapper mapper;
    private final AuditService audit;

    public PatientService(PatientRepository repository, PatientMapper mapper, AuditService audit) {
        this.repository = repository;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<PatientResponse> search(PatientFilter filter, Pageable pageable) {
        return repository.findAll(PatientSpecifications.matching(filter), pageable).map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PatientResponse get(long id) {
        return mapper.toResponse(find(id));
    }

    @Transactional(readOnly = true)
    public List<String> cities() {
        return repository.findDistinctCities();
    }

    @Transactional
    public PatientResponse create(PatientRequest request) {
        PatientDetails details = mapper.toDetails(request);
        if (repository.existsByEmailIgnoreCase(details.email())) {
            throw duplicateEmail(details.email());
        }
        // Flush now so the generated id, timestamps and any constraint violation are known before returning.
        Patient patient = repository.saveAndFlush(new Patient(details));
        audit.recordCreate(AUDIT_ENTITY, patient.getId(), auditLabel(patient), patient.details());
        return mapper.toResponse(patient);
    }

    @Transactional
    public PatientResponse update(long id, PatientRequest request) {
        Patient patient = find(id);
        if (request.version() != patient.getVersion()) {
            throw new ConflictException("This patient was changed by someone else since you opened it. "
                    + "Reload it and apply your changes again.");
        }
        PatientDetails details = mapper.toDetails(request);
        if (repository.existsByEmailIgnoreCaseAndIdNot(details.email(), id)) {
            throw duplicateEmail(details.email());
        }
        PatientDetails before = patient.details();
        patient.updateDetails(details);
        repository.saveAndFlush(patient);
        audit.recordUpdate(AUDIT_ENTITY, id, auditLabel(patient), before, patient.details());
        return mapper.toResponse(patient);
    }

    @Transactional
    public void delete(long id) {
        Patient patient = find(id);
        repository.delete(patient);
        audit.recordDelete(AUDIT_ENTITY, id, auditLabel(patient), patient.details());
    }

    private static String auditLabel(Patient patient) {
        return "patient " + patient.displayName();
    }

    private Patient find(long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Patient", id));
    }

    private static ConflictException duplicateEmail(String email) {
        return new ConflictException("A patient with the email " + email + " already exists.");
    }
}
