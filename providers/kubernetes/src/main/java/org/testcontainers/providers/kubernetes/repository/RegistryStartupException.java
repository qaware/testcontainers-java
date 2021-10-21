package org.testcontainers.providers.kubernetes.repository;

public class RegistryStartupException extends Exception {
    public RegistryStartupException(String message) {
        super(message);
    }
}
