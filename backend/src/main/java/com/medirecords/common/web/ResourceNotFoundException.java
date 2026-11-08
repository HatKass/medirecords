package com.medirecords.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 404 as ProblemDetail. Being an {@link ErrorResponseException}, Spring MVC renders it without extra handlers. */
public class ResourceNotFoundException extends ErrorResponseException {

    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, problem(resource, id), null);
    }

    private static ProblemDetail problem(String resource, Object id) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                resource + " with id " + id + " does not exist.");
        problem.setTitle(resource + " not found");
        return problem;
    }
}
