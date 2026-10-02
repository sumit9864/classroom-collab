package com.classroom.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NetworkUtilTest {

    @Test
    public void testIsInterfaceNameAllowed() {
        // Allowed interfaces
        assertTrue(NetworkUtil.isInterfaceNameAllowed("eth0"));
        assertTrue(NetworkUtil.isInterfaceNameAllowed("wlan0"));
        assertTrue(NetworkUtil.isInterfaceNameAllowed("en0"));
        assertTrue(NetworkUtil.isInterfaceNameAllowed("Wi-Fi"));
        assertTrue(NetworkUtil.isInterfaceNameAllowed("Ethernet"));
        assertTrue(NetworkUtil.isInterfaceNameAllowed("Local Area Connection"));
        
        // Disallowed interfaces (VirtualBox, VMWare, WSL, VPN, etc.)
        assertFalse(NetworkUtil.isInterfaceNameAllowed("vboxnet0"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("VirtualBox Host-Only Ethernet Adapter"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("vmnet8"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("VMware Network Adapter VMnet1"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("vEthernet (WSL)"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("wsl"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("docker0"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("tailscale0"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("OpenVPN TAP-Windows6"));
        assertFalse(NetworkUtil.isInterfaceNameAllowed("Hyper-V Virtual Ethernet Adapter"));
    }
}
