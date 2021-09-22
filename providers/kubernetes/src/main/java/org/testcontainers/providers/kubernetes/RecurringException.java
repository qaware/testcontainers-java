package org.testcontainers.providers.kubernetes;

public class RecurringException extends Exception  {

    public RecurringException(String message, Throwable cause) {
        super(message, cause);
    }

    public RecurringException(Throwable cause) {
        this("The current operation failed because of a previous exception. See inner exception for more details.", cause);
    }

}
