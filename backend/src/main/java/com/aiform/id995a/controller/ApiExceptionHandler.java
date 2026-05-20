package com.aiform.id995a.controller;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(IOException.class)
  public ResponseEntity<String> handleIOException(IOException exception) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .contentType(MediaType.TEXT_PLAIN)
        .body(exception.getMessage());
  }
}
