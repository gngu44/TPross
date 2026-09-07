package com.tpross.exception;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("A user with this email already exists.");
    }
}
