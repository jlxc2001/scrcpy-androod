package com.jlxc.androidotgscrcpy.adb;

public class AdbException extends Exception {
    public AdbException(String message) { super(message); }
    public AdbException(String message, Throwable cause) { super(message, cause); }
}
