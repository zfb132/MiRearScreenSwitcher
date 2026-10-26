package com.tgwgroup.MiRearScreenSwitcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PrivilegePolicyTest {
    @Test
    public void prefersAxManagerWhenBothManagersAreAvailable() {
        assertEquals(PrivilegePolicy.AX_MANAGER, PrivilegePolicy.selectBackend(true, true));
    }

    @Test
    public void usesAxManagerWithoutShizuku() {
        assertEquals(PrivilegePolicy.AX_MANAGER, PrivilegePolicy.selectBackend(true, false));
    }

    @Test
    public void fallsBackToAnAuthorizedShizuku() {
        assertEquals(PrivilegePolicy.SHIZUKU, PrivilegePolicy.selectBackend(false, true));
    }

    @Test
    public void reportsUnavailableWhenNeitherManagerIsAuthorized() {
        assertEquals(PrivilegePolicy.NONE, PrivilegePolicy.selectBackend(false, false));
    }

    @Test
    public void acceptsOnlyRootOrShellUid() {
        assertEquals(0, PrivilegePolicy.parseAxUid("0"));
        assertEquals(2000, PrivilegePolicy.parseAxUid(" 2000\n"));
        assertEquals(-1, PrivilegePolicy.parseAxUid("10342"));
        assertEquals(-1, PrivilegePolicy.parseAxUid("-1"));
    }

    @Test
    public void rejectsEmptyMalformedAndMixedCommandOutput() {
        assertEquals(-1, PrivilegePolicy.parseAxUid(null));
        assertEquals(-1, PrivilegePolicy.parseAxUid(""));
        assertEquals(-1, PrivilegePolicy.parseAxUid("permission denied"));
        assertEquals(-1, PrivilegePolicy.parseAxUid("uid=2000(shell)"));
        assertEquals(-1, PrivilegePolicy.parseAxUid("2000\npermission denied"));
    }
}
