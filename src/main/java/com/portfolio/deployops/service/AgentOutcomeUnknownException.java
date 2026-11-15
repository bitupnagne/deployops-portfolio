package com.portfolio.deployops.service;

/** The remote operation may have started; callers must not compensate or retry it automatically. */
public class AgentOutcomeUnknownException extends RemoteExecutionException {
    public AgentOutcomeUnknownException(String message) { super(message); }
}
