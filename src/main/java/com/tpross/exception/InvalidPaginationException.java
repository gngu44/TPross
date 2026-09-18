package com.tpross.exception;

public class InvalidPaginationException extends RuntimeException {

    public InvalidPaginationException() {
        super("Page must be nonnegative, size must be between 1 and 100, "
                + "and page times size must not exceed 2147483647.");
    }
}
