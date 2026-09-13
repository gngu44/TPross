package com.tpross.exception;

public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException() {
        super("Source and destination accounts must be different.");
    }
}
