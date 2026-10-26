package com.tgwgroup.MiRearScreenSwitcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** One application-wide connection policy: AxManager first, Shizuku as fallback. */
public final class PrivilegedAccess {
    public interface OnBinderReceivedListener {
        void onBinderReceived();
    }

    public interface OnBinderDeadListener {
        void onBinderDead();
    }

    public interface OnRequestPermissionResultListener {
        void onRequestPermissionResult(int requestCode, int grantResult);
    }

    private static final String TAG = "PrivilegedAccess";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor();
    private static final AtomicBoolean PROBING = new AtomicBoolean();
    private static final Map<ServiceConnection, Binding> BINDINGS = new LinkedHashMap<>();
    private static final Set<OnBinderReceivedListener> RECEIVED = new CopyOnWriteArraySet<>();
    private static final Set<OnBinderDeadListener> DEAD = new CopyOnWriteArraySet<>();
    private static final Set<OnRequestPermissionResultListener> PERMISSIONS = new CopyOnWriteArraySet<>();
    private static Context context;
    private static AxTaskService axService;
    private static volatile boolean axAvailable;
    private static volatile int axUid = -1;
    private static volatile String axError = "";
    private static boolean requestFallback;
    private static int permissionRequestCode;
    private static String selected = PrivilegePolicy.NONE;

    private PrivilegedAccess() {}

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }

    public static void init(Context appContext) {
        if (context != null) return;
        context = appContext.getApplicationContext();
        axService = new AxTaskService(context);
        Shizuku.addBinderReceivedListenerSticky(() -> MAIN.post(PrivilegedAccess::reconcile));
        Shizuku.addBinderDeadListener(() -> MAIN.post(PrivilegedAccess::reconcile));
        Shizuku.addRequestPermissionResultListener((code, result) -> MAIN.post(() -> {
            reconcile();
            for (OnRequestPermissionResultListener listener : PERMISSIONS) {
                listener.onRequestPermissionResult(code, result);
            }
        }));
        refresh();
        WORKER.scheduleWithFixedDelay(() -> MAIN.post(() -> {
            if (!BINDINGS.isEmpty() || !RECEIVED.isEmpty()) refresh();
        }), 15, 15, TimeUnit.SECONDS);
    }

    public static boolean isAxManagerInstalled() {
        if (context == null) return false;
        try {
            context.getPackageManager().getApplicationInfo(AxTaskService.MANAGER_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean isChecking() {
        return PROBING.get();
    }

    public static String getAxError() {
        return axError;
    }

    public static String getBackendName() {
        return PrivilegePolicy.selectBackend(axAvailable, !axAvailable && shizukuGranted());
    }

    public static boolean pingBinder() {
        try {
            return axAvailable || Shizuku.pingBinder();
        } catch (RuntimeException e) {
            return axAvailable;
        }
    }

    public static int checkSelfPermission() {
        if (axAvailable || shizukuGranted()) return PackageManager.PERMISSION_GRANTED;
        return PackageManager.PERMISSION_DENIED;
    }

    public static int getUid() {
        if (axAvailable) return axUid;
        try {
            return Shizuku.pingBinder() ? Shizuku.getUid() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean shizukuGranted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void refresh() {
        if (context == null || !PROBING.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            boolean available = false;
            int uid = -1;
            String error = "";
            Process process = null;
            try {
                process = new ProcessBuilder(AxTaskService.executable(context).getAbsolutePath(),
                        "-c", "id -u").redirectErrorStream(true).start();
                // A permission dialog may be shown by AxManager on the first invocation.
                int exitCode = ProcessWaiter.awaitExit(process, 30_000);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream stream = process.getInputStream()) {
                    byte[] bytes = new byte[1024];
                    int count;
                    while ((count = stream.read(bytes)) != -1) output.write(bytes, 0, count);
                }
                String value = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
                int parsedUid = PrivilegePolicy.parseAxUid(value);
                if (exitCode == 0 && parsedUid != -1) {
                    uid = parsedUid;
                    available = true;
                } else {
                    error = value.isEmpty() ? "AxManager server unavailable or MRSS not allowed" : value;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "AxManager authorization interrupted";
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (process != null) process.destroy();
            }
            final boolean ready = available;
            final int actualUid = uid;
            final String failure = error;
            MAIN.post(() -> {
                axAvailable = ready;
                axUid = actualUid;
                axError = failure;
                PROBING.set(false);
                reconcile();
                if (requestFallback) {
                    requestFallback = false;
                    try {
                        if (!ready && !shizukuGranted() && Shizuku.pingBinder()) {
                            Shizuku.requestPermission(permissionRequestCode);
                            return; // Shizuku's permission callback reports the result.
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot request Shizuku permission", e);
                    }
                    for (OnRequestPermissionResultListener listener : PERMISSIONS) {
                        listener.onRequestPermissionResult(permissionRequestCode, checkSelfPermission());
                    }
                }
            });
        });
    }

    public static void requestPermission(int code) {
        MAIN.post(() -> {
            requestFallback = true;
            permissionRequestCode = code;
            refresh();
        });
    }

    public static void addBinderReceivedListenerSticky(OnBinderReceivedListener listener) {
        RECEIVED.add(listener);
        if (checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            MAIN.post(() -> {
                if (RECEIVED.contains(listener)) listener.onBinderReceived();
            });
        }
    }

    public static void removeBinderReceivedListener(OnBinderReceivedListener listener) {
        RECEIVED.remove(listener);
    }

    public static void addBinderDeadListener(OnBinderDeadListener listener) {
        DEAD.add(listener);
    }

    public static void removeBinderDeadListener(OnBinderDeadListener listener) {
        DEAD.remove(listener);
    }

    public static void addRequestPermissionResultListener(OnRequestPermissionResultListener listener) {
        PERMISSIONS.add(listener);
    }

    public static void removeRequestPermissionResultListener(OnRequestPermissionResultListener listener) {
        PERMISSIONS.remove(listener);
    }

    public static void bindTaskService(ServiceConnection connection) {
        onMain(() -> {
            Binding binding = BINDINGS.get(connection);
            if (binding == null) {
                binding = new Binding(connection);
                BINDINGS.put(connection, binding);
            }
            binding.connect();
            if (!axAvailable) refresh();
        });
    }

    public static void unbindTaskService(ServiceConnection connection) {
        onMain(() -> {
            Binding binding = BINDINGS.remove(connection);
            if (binding != null) binding.release(false);
            // Removing one client must not kill the shared TaskService used by other features.
        });
    }

    private static void reconcile() {
        String next = getBackendName();
        if (!next.equals(selected)) {
            Log.i(TAG, "Privilege backend: " + selected + " -> " + next);
            boolean wasReady = !PrivilegePolicy.NONE.equals(selected);
            selected = next;
            for (Binding binding : new ArrayList<>(BINDINGS.values())) binding.release(true);
            if (wasReady && PrivilegePolicy.NONE.equals(next)) {
                for (OnBinderDeadListener listener : DEAD) listener.onBinderDead();
            }
            if (!PrivilegePolicy.NONE.equals(next)) {
                for (OnBinderReceivedListener listener : RECEIVED) listener.onBinderReceived();
            }
            // Notify clients when access changes, not after every periodic probe.
            for (OnRequestPermissionResultListener listener : PERMISSIONS) {
                listener.onRequestPermissionResult(0, checkSelfPermission());
            }
        }
        for (Binding binding : new ArrayList<>(BINDINGS.values())) binding.connect();
    }

    private static final class Binding implements ServiceConnection {
        final Shizuku.UserServiceArgs args;
        final ServiceConnection client;
        String transport = PrivilegePolicy.NONE;
        IBinder binder;
        ServiceConnection remote;
        int generation;

        Binding(ServiceConnection client) {
            this.args = new Shizuku.UserServiceArgs(component())
                    .daemon(false)
                    .processNameSuffix("task_service")
                    .debuggable(false)
                    .version(3);
            this.client = client;
        }

        void connect() {
            if (BINDINGS.get(client) != this || !PrivilegePolicy.NONE.equals(transport)) return;
            try {
                if (axAvailable) {
                    transport = PrivilegePolicy.AX_MANAGER;
                    onServiceConnected(component(), axService.asBinder());
                } else if (shizukuGranted()) {
                    transport = PrivilegePolicy.SHIZUKU;
                    final int attempt = ++generation;
                    remote = new ServiceConnection() {
                        @Override
                        public void onServiceConnected(ComponentName name, IBinder service) {
                            MAIN.post(() -> {
                                if (generation == attempt) Binding.this.onServiceConnected(name, service);
                            });
                        }
                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                            MAIN.post(() -> {
                                if (generation == attempt) Binding.this.onServiceDisconnected(name);
                            });
                        }
                        @Override
                        public void onBindingDied(ComponentName name) {
                            onServiceDisconnected(name);
                        }
                        @Override
                        public void onNullBinding(ComponentName name) {
                            onServiceDisconnected(name);
                        }
                    };
                    Shizuku.bindUserService(args, remote);
                }
            } catch (Exception e) {
                transport = PrivilegePolicy.NONE;
                Log.w(TAG, "TaskService bind failed", e);
            }
        }

        void release(boolean notify) {
            boolean connected = binder != null;
            binder = null;
            generation++;
            if (PrivilegePolicy.SHIZUKU.equals(transport)) {
                try {
                    Shizuku.unbindUserService(args, remote, false);
                } catch (Exception e) {
                    Log.w(TAG, "TaskService unbind failed", e);
                }
            }
            transport = PrivilegePolicy.NONE;
            remote = null;
            if (notify && connected) client.onServiceDisconnected(component());
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            onMain(() -> {
                if (BINDINGS.get(client) != this || !transport.equals(getBackendName())) return;
                try {
                    binder = service;
                    client.onServiceConnected(name, service);
                } catch (Exception e) {
                    release(true);
                    Log.w(TAG, "TaskService initialization failed", e);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            MAIN.post(() -> {
                if (BINDINGS.get(client) == this) {
                    release(true);
                    MAIN.postDelayed(this::connect, 2000);
                }
            });
        }

        private ComponentName component() {
            return new ComponentName(context, TaskService.class);
        }
    }
}
