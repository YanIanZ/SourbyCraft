package dev.iyanz.sourbycraft.swm.api.exceptions;

public class CorruptedWorldException extends SlimeException {
    public CorruptedWorldException(String message) { super(message); }
    public CorruptedWorldException(String message, Throwable cause) { super(message, cause); }
}
