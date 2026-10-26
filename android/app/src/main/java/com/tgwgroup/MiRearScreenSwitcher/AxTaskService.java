package com.tgwgroup.MiRearScreenSwitcher;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Uses AxManager's documented axsh entry point, without Shizuku interception. */
final class AxTaskService extends TaskService {
    static final String MANAGER_PACKAGE = "frb.axeron.manager";
    private final Context context;

    AxTaskService(Context context) {
        this.context = context.getApplicationContext();
    }

    static File executable(Context context) throws IOException {
        try {
            // Resolve on every invocation: Android changes this path after manager updates.
            String directory = context.getPackageManager()
                    .getApplicationInfo(MANAGER_PACKAGE, 0).nativeLibraryDir;
            File executable = new File(directory, "libaxsh.so");
            if (!executable.isFile() || !executable.canExecute()) {
                throw new IOException("AxManager does not provide an executable libaxsh.so; update AxManager");
            }
            return executable;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IOException("AxManager is not installed", e);
        }
    }

    @Override
    protected ProcessBuilder shell(String command) throws IOException {
        return new ProcessBuilder(executable(context).getAbsolutePath(), "-c", command).redirectErrorStream(true);
    }

    @Override
    public void destroy() {
        // This binder lives in MRSS, so never call TaskService's System.exit here.
    }
}
