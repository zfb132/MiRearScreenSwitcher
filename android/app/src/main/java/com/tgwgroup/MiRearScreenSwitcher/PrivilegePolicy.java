package com.tgwgroup.MiRearScreenSwitcher;

/** Pure policy shared by permission probes and connection selection. */
final class PrivilegePolicy {
    static final String AX_MANAGER = "AxManager";
    static final String SHIZUKU = "Shizuku";
    static final String NONE = "None";

    private PrivilegePolicy() {}

    static String selectBackend(boolean axAvailable, boolean shizukuGranted) {
        if (axAvailable) return AX_MANAGER;
        return shizukuGranted ? SHIZUKU : NONE;
    }

    static int parseAxUid(String output) {
        if (output == null) return -1;
        String uid = output.trim();
        if ("0".equals(uid)) return 0;
        if ("2000".equals(uid)) return 2000;
        return -1;
    }
}
