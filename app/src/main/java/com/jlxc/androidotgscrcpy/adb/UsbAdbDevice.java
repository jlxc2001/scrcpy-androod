package com.jlxc.androidotgscrcpy.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

public final class UsbAdbDevice {
    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface adbInterface;
    private final UsbEndpoint bulkIn;
    private final UsbEndpoint bulkOut;

    private UsbAdbDevice(UsbDevice device, UsbDeviceConnection connection, UsbInterface adbInterface, UsbEndpoint bulkIn, UsbEndpoint bulkOut) {
        this.device = device;
        this.connection = connection;
        this.adbInterface = adbInterface;
        this.bulkIn = bulkIn;
        this.bulkOut = bulkOut;
    }

    public static boolean hasAdbInterface(UsbDevice device) {
        return findAdbInterface(device) != null;
    }

    public static UsbAdbDevice open(UsbDevice device, UsbDeviceConnection connection) throws AdbException {
        UsbInterface intf = findAdbInterface(device);
        if (intf == null) throw new AdbException("ADB interface not found. Check USB debugging and USB mode on target phone.");

        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
            }
        }
        if (in == null || out == null) throw new AdbException("ADB bulk endpoints not found");
        if (!connection.claimInterface(intf, true)) throw new AdbException("Failed to claim ADB interface");
        return new UsbAdbDevice(device, connection, intf, in, out);
    }

    private static UsbInterface findAdbInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 0xff && intf.getInterfaceSubclass() == 0x42 && intf.getInterfaceProtocol() == 0x01) {
                return intf;
            }
        }
        return null;
    }

    public int write(byte[] data, int timeoutMs) throws AdbException {
        int offset = 0;
        while (offset < data.length) {
            int count = Math.min(data.length - offset, bulkOut.getMaxPacketSize() * 16);
            int written = connection.bulkTransfer(bulkOut, data, offset, count, timeoutMs);
            if (written <= 0) throw new AdbException("USB bulk write failed: " + written);
            offset += written;
        }
        return offset;
    }

    public int read(byte[] buffer, int length, int timeoutMs) throws AdbException {
        int read = connection.bulkTransfer(bulkIn, buffer, 0, length, timeoutMs);
        if (read < 0) throw new AdbException("USB bulk read failed: " + read);
        return read;
    }

    public void close() {
        try { connection.releaseInterface(adbInterface); } catch (Throwable ignored) {}
        try { connection.close(); } catch (Throwable ignored) {}
    }

    public String getName() {
        return device.getDeviceName() + " VID=" + device.getVendorId() + " PID=" + device.getProductId();
    }
}
