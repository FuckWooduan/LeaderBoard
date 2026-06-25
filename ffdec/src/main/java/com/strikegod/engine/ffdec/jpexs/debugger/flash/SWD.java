package com.strikegod.engine.ffdec.jpexs.debugger.flash;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Minimal SWD model used only to keep upstream FFDEC debug-export signatures
 * source-compatible in the Engine build. StrikeGod never emits Flash debugger
 * files at runtime.
 */
public class SWD {

    public static final int bitmapAction = 1;

    public SWD(int version, List<DebugItem> items) {}

    public void saveTo(OutputStream outputStream) throws IOException {
        outputStream.write(new byte[0]);
    }

    public abstract static class DebugItem {}

    public static final class DebugId extends DebugItem {
        public DebugId(byte[] debugId) {}
    }

    public static final class DebugScript extends DebugItem {
        public DebugScript(int moduleId, int bitmap, String name, String text) {}
    }

    public static final class DebugOffset extends DebugItem {
        public DebugOffset(int moduleId, int line, int offset) {}
    }

    public static final class DebugBreakpoint extends DebugItem {
        public DebugBreakpoint(int moduleId, int line) {}
    }

    public static final class DebugRegisters extends DebugItem {
        public DebugRegisters(int offset, List<Integer> indexes, List<String> names) {}
    }
}
