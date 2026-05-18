package dev.iyanz.sourbycraft.swm.api.exceptions;

public class InvalidWorldException extends SlimeException {
    public InvalidWorldException(String message) { super(message); }
    public InvalidWorldException(String message, Throwable cause) { super(message, cause); }
}
