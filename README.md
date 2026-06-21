# Dynamic Instrumentation Proof of Concept

A small dynamic observability agent for the JVM. It attaches to a running HTTP calculator,
stops at a configurable breakpoint, and reads out the local variables and the whole call stack
at that point, without any of that code living in the calculator itself.

There are two parts: a Spring Boot calculator (`target-app/`) that is plain business logic with
no logging or tracing of any kind, and an agent (`agent/`) that attaches over JDWP using JDI and
captures the state.

## Execution Instructions

You only need Docker installed. Everything else is built inside the image.

1. Build the image (run from the repo root):

   ```bash
   docker build -t hyperprobe .
   ```

2. Run the container:

   ```bash
   docker run --rm -p 8080:8080 hyperprobe
   ```

   You should see the calculator start up and then the agent attach and arm the breakpoint:

   ```
   [agent] attached to localhost:5005, breakpoint = com.hyperprobe.target.AdditionEngine.add
   [agent] breakpoint armed at com.hyperprobe.target.AdditionEngine:9
   ```

   Keep this terminal open, the captured state is printed here.

3. In another terminal, hit the endpoint:

   ```bash
   curl "http://localhost:8080/calculate?op=add&a=10&b=20"
   ```

   The calculator responds with `{"op":"add","a":10.0,"b":20.0,"result":30.0}`, and at the same
   time the agent terminal prints a JSON dump of the local variables at `AdditionEngine.add` plus
   every caller frame up the stack. The same JSON is also saved to `/app/captures/` in the
   container. Supported operations are `add`, `sub`, `mul`, and `div`.

The breakpoint is just an environment variable, so you can point it somewhere else without
rebuilding. It takes either `Class.method` or `Class:line`:

```bash
docker run --rm -p 8080:8080 -e BREAKPOINT=com.hyperprobe.target.MathService.compute hyperprobe
```

## Architecture Write-up

The calculator is started with the standard JVM debug flag
(`-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`). That is the only thing
done to it. Its source is never touched and nothing is recompiled with extra logic. The agent is
a separate process that connects to that debug port using JDI (the Java Debug Interface) via the
`SocketAttachingConnector`, and sets a breakpoint through the `EventRequestManager`. If the class
isn't loaded yet it waits for a class-prepare event and arms the breakpoint then. So the agent
hooks into the runtime purely as an external debugger would, and the calculator stays unaware of
it.

When the breakpoint is hit, JDI hands the agent the event with a suspend policy that pauses only
that one request thread. The agent walks the thread's frames (`ThreadReference.frames()`), and for
each frame it reads the class/method/line, the `this` object, and the locals in scope through
`frame.visibleVariables()` and `frame.getValue()`. Primitives and strings are read directly and
objects are shown as `type@id` (it does not call any methods on the target, to avoid side
effects). It writes the result to stdout and a JSON file, then resumes the thread so the HTTP
request finishes normally.

## Limitations

- Running with JDWP enabled has overhead, and reading each variable over the debug protocol while
  the thread is paused is not free. Fine for a PoC, not for hot paths.
- The request thread is suspended for the length of the capture, so a breakpoint on a busy method
  would add real latency.
- Object values are only shown as `type@id`; their fields aren't expanded.
- The whole caller chain is captured, including framework frames, so the output is verbose.
- The debug port is open, which would be a security concern outside a local/sandboxed setup.

If this were going into a high-traffic production system I'd move away from the debugger attach
and use a JVMTI agent or bytecode instrumentation (e.g. ByteBuddy/ASM) so state can be captured
without suspending threads, sample or conditionally trigger captures instead of catching every
hit, do the serialization asynchronously off the request thread, and lock down the transport
(localhost only, auth) with rate limits so the instrumentation can't take the host down.

## Notes

- The default breakpoint is at the method entry, so `result` isn't computed yet there and only
  `a` and `b` show up. Use a line breakpoint like `AdditionEngine:10` to capture `result` too.
- Built on Java 21. The runtime image is a full JDK rather than a JRE because the agent needs the
  `jdk.jdi` module.
