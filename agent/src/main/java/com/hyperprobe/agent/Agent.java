package com.hyperprobe.agent;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * External instrumentation agent.
 *
 * Attaches to the target JVM over JDWP (Java Debug Wire Protocol) using JDI, arms a breakpoint at a
 * configurable location, and on each hit captures the local variables in scope plus every caller
 * frame up the call stack. State is written to stdout and to a JSON file. The breakpoint thread is
 * suspended only for the duration of the capture, then resumed so the HTTP request completes.
 *
 * The target application is never modified: JDWP is enabled with a JVM launch flag only.
 */
public class Agent {

    private static String breakpointSpec;
    private static String className;
    private static String methodName; // null when a line breakpoint is used
    private static int lineNumber = -1;
    private static Path captureDir;

    public static void main(String[] args) throws Exception {
        breakpointSpec = System.getProperty("breakpointSpec", "com.hyperprobe.target.AdditionEngine.add");
        String jdwpAddress = System.getProperty("jdwpAddress", "localhost:5005");
        captureDir = Path.of(System.getProperty("captureDir", "captures"));
        Files.createDirectories(captureDir);

        parseBreakpoint(breakpointSpec);

        VirtualMachine vm = attachWithRetry(jdwpAddress);
        System.out.println("[agent] attached to " + jdwpAddress + ", breakpoint = " + breakpointSpec);

        armBreakpoint(vm);
        runEventLoop(vm);
    }

    /** Accepts either "fully.qualified.ClassName.methodName" or "fully.qualified.ClassName:lineNumber". */
    private static void parseBreakpoint(String spec) {
        int colon = spec.indexOf(':');
        if (colon >= 0) {
            className = spec.substring(0, colon);
            lineNumber = Integer.parseInt(spec.substring(colon + 1));
        } else {
            int dot = spec.lastIndexOf('.');
            className = spec.substring(0, dot);
            methodName = spec.substring(dot + 1);
        }
    }

    private static VirtualMachine attachWithRetry(String address)
            throws InterruptedException, IllegalConnectorArgumentsException {
        String[] hostPort = address.split(":");
        String host = hostPort[0];
        String port = hostPort[1];

        AttachingConnector connector = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(c.name())) {
                connector = c;
                break;
            }
        }
        if (connector == null) {
            throw new IllegalStateException("SocketAttach connector not available");
        }

        Map<String, Connector.Argument> params = connector.defaultArguments();
        params.get("hostname").setValue(host);
        params.get("port").setValue(port);

        IOException last = null;
        for (int attempt = 1; attempt <= 60; attempt++) {
            try {
                return connector.attach(params);
            } catch (IOException e) {
                last = e;
                System.out.println("[agent] target not ready, retrying attach (" + attempt + ")...");
                Thread.sleep(1000);
            }
        }
        throw new IllegalStateException("Could not attach to " + address, last);
    }

    private static void armBreakpoint(VirtualMachine vm) {
        // If the target class is already loaded, set the breakpoint now.
        for (ReferenceType type : vm.classesByName(className)) {
            setBreakpoint(vm, type);
        }
        // Otherwise catch it when it loads (typically during the first matching request).
        ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
        cpr.addClassFilter(className);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        cpr.enable();
    }

    private static void setBreakpoint(VirtualMachine vm, ReferenceType type) {
        List<Location> locations;
        try {
            if (methodName != null) {
                locations = type.methodsByName(methodName).stream()
                        .map(Method::location)
                        .filter(Objects::nonNull)
                        .toList();
            } else {
                locations = type.locationsOfLine(lineNumber);
            }
        } catch (AbsentInformationException e) {
            System.out.println("[agent] no debug info for " + type.name() + "; cannot resolve breakpoint location");
            return;
        }
        for (Location loc : locations) {
            BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(loc);
            bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            bp.enable();
            System.out.println("[agent] breakpoint armed at " + loc);
        }
    }

    private static void runEventLoop(VirtualMachine vm) {
        EventQueue queue = vm.eventQueue();
        while (true) {
            EventSet set;
            try {
                set = queue.remove();
            } catch (InterruptedException e) {
                return;
            }
            for (Event event : set) {
                if (event instanceof ClassPrepareEvent cpe) {
                    setBreakpoint(vm, cpe.referenceType());
                } else if (event instanceof BreakpointEvent bpe) {
                    try {
                        capture(bpe.thread());
                    } catch (Exception e) {
                        System.out.println("[agent] capture failed: " + e);
                    }
                } else if (event instanceof VMDisconnectEvent) {
                    System.out.println("[agent] target disconnected, exiting");
                    return;
                }
            }
            // Resume the suspended thread so the in-flight HTTP request continues.
            set.resume();
        }
    }

    private static void capture(ThreadReference thread)
            throws IncompatibleThreadStateException, IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"timestamp\": ").append(jsonString(Instant.now().toString())).append(",\n");
        json.append("  \"breakpoint\": ").append(jsonString(breakpointSpec)).append(",\n");
        json.append("  \"thread\": ").append(jsonString(thread.name())).append(",\n");
        json.append("  \"frames\": [\n");

        List<StackFrame> frames = thread.frames();
        for (int i = 0; i < frames.size(); i++) {
            StackFrame frame = frames.get(i);
            Location loc = frame.location();
            json.append("    {\n");
            json.append("      \"depth\": ").append(i).append(",\n");
            json.append("      \"class\": ").append(jsonString(loc.declaringType().name())).append(",\n");
            json.append("      \"method\": ").append(jsonString(loc.method().name())).append(",\n");
            json.append("      \"line\": ").append(loc.lineNumber()).append(",\n");

            ObjectReference thisObj = frame.thisObject();
            json.append("      \"this\": ")
                    .append(thisObj == null ? "null" : jsonString(describe(thisObj)))
                    .append(",\n");

            json.append("      \"locals\": ");
            try {
                List<LocalVariable> vars = frame.visibleVariables();
                if (vars.isEmpty()) {
                    json.append("{}");
                } else {
                    json.append("{\n");
                    for (int v = 0; v < vars.size(); v++) {
                        LocalVariable var = vars.get(v);
                        Value value = frame.getValue(var);
                        json.append("        ").append(jsonString(var.name())).append(": ").append(renderValue(value));
                        json.append(v == vars.size() - 1 ? "\n" : ",\n");
                    }
                    json.append("      }");
                }
            } catch (AbsentInformationException e) {
                json.append(jsonString("<no debug info>"));
            }

            json.append("\n    }");
            json.append(i == frames.size() - 1 ? "\n" : ",\n");
        }

        json.append("  ]\n");
        json.append("}\n");

        String out = json.toString();
        System.out.println(out);

        Path file = captureDir.resolve("capture-" + System.currentTimeMillis() + ".json");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file))) {
            writer.print(out);
        }
        System.out.println("[agent] wrote " + file);
    }

    private static String describe(ObjectReference obj) {
        return obj.referenceType().name() + "@" + obj.uniqueID();
    }

    /** Renders a JDI Value as a valid JSON token (number/boolean/string). */
    private static String renderValue(Value v) {
        if (v == null) return "null";
        if (v instanceof BooleanValue b) return Boolean.toString(b.value());
        if (v instanceof CharValue c) return jsonString(String.valueOf(c.value()));
        if (v instanceof ByteValue b) return Byte.toString(b.value());
        if (v instanceof ShortValue s) return Short.toString(s.value());
        if (v instanceof IntegerValue i) return Integer.toString(i.value());
        if (v instanceof LongValue l) return Long.toString(l.value());
        if (v instanceof FloatValue f) {
            float val = f.value();
            return Float.isFinite(val) ? Float.toString(val) : jsonString(Float.toString(val));
        }
        if (v instanceof DoubleValue d) {
            double val = d.value();
            return Double.isFinite(val) ? Double.toString(val) : jsonString(Double.toString(val));
        }
        if (v instanceof StringReference s) return jsonString(s.value());
        if (v instanceof ArrayReference a) return jsonString(a.referenceType().name() + "[" + a.length() + "]");
        if (v instanceof ObjectReference o) return jsonString(describe(o));
        return jsonString(v.toString());
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append("\"").toString();
    }
}
