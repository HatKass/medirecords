package com.medirecords.patient;

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

    private final PatientRepository repository;
    private final PatientMapper mapper;

    public PatientService(PatientRepository repository, PatientMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
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
        return mapper.toResponse(repository.saveAndFlush(new Patient(details)));
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
        patient.updateDetails(details);
        return mapper.toResponse(repository.saveAndFlush(patient));
    }

    @Transactional
    public void delete(long id) {
        repository.delete(find(id));
    }

    private Patient find(long id) {
        return repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Patient", id));
    }

    private static ConflictException duplicateEmail(String email) {
        return new ConflictException("A patient with the email " + email + " already exists.");
    }
}
